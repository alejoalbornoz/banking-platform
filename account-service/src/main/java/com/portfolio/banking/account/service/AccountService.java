package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.dto.PageResponse;
import com.portfolio.banking.account.exception.ConcurrentUpdateException;
import com.portfolio.banking.account.exception.OperationKeyReusedException;
import com.portfolio.banking.account.exception.ResourceNotFoundException;
import com.portfolio.banking.account.mapper.IAccountMapper;
import com.portfolio.banking.account.mapper.ILedgerMapper;
import com.portfolio.banking.account.messaging.IAccountEventPublisher;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.model.LedgerDirection;
import com.portfolio.banking.account.model.LedgerEntry;
import com.portfolio.banking.account.pagination.KeysetPage;
import com.portfolio.banking.account.repository.IAccountRepository;
import com.portfolio.banking.account.repository.ILedgerEntryRepository;
import com.portfolio.banking.common.event.AccountCreatedEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AccountService implements IAccountService {

    private static final int ACCOUNT_NUMBER_LENGTH = 12;
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    /** Matches the NUMERIC(19,2) scale every money column in this service uses. */
    private static final BigDecimal ZERO_BALANCE = BigDecimal.ZERO.setScale(2);

    private final IAccountRepository accountRepository;
    private final ILedgerEntryRepository ledgerEntryRepository;
    private final IAccountMapper accountMapper;
    private final ILedgerMapper ledgerMapper;
    private final IAccountEventPublisher accountEventPublisher;
    private final RetryTemplate optimisticLockRetryTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountService(IAccountRepository accountRepository,
                           ILedgerEntryRepository ledgerEntryRepository,
                           IAccountMapper accountMapper,
                           ILedgerMapper ledgerMapper,
                           IAccountEventPublisher accountEventPublisher,
                           RetryTemplate optimisticLockRetryTemplate,
                           TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.accountEventPublisher = accountEventPublisher;
        this.optimisticLockRetryTemplate = optimisticLockRetryTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public AccountResponse createAccount(UUID ownerId, CreateAccountRequest request) {
        Account account = new Account(
                generateUniqueAccountNumber(),
                ownerId,
                request.openingBalance(),
                request.currency()
        );
        // saveAndFlush, not save: @CreationTimestamp and @UpdateTimestamp are
        // populated by Hibernate when the INSERT is written, which a plain
        // save() defers to commit - i.e. until after we've already mapped the
        // response. Without the flush the caller gets createdAt/updatedAt as
        // null on create, and only sees real values on subsequent reads.
        Account saved = accountRepository.saveAndFlush(account);

        // The opening balance is money appearing on the account, so it gets a
        // ledger entry like any other movement. Without it, every account
        // opened with funds would start out failing reconciliation.
        // Zero-balance accounts need no entry: an empty ledger already sums to
        // zero, and the amount > 0 constraint would reject one anyway.
        if (saved.getBalance().signum() > 0) {
            ledgerEntryRepository.save(
                    LedgerEntry.opening(saved.getId(), saved.getBalance(), saved.getCurrency()));
        }

        publishAfterCommit(new AccountCreatedEvent(
                saved.getId(), saved.getAccountNumber(), saved.getOwnerId(),
                saved.getBalance(), saved.getCurrency()));
        return accountMapper.toResponse(saved);
    }

    /**
     * Defers the publish until the surrounding transaction actually commits.
     * Without this, a rollback after the save (e.g. a later validation
     * failure in the same transaction) would still have announced an account
     * that never really exists.
     * <p>
     * This is NOT the full fix for the dual-write problem (DB commit and
     * message publish are still two separate operations - the process could
     * crash between them and the event would be lost even though the account
     * exists). The proper fix is the outbox pattern: write the event to an
     * "outbox" table in the SAME transaction as the account, and have a
     * separate poller/relay publish it to RabbitMQ afterwards. See the
     * Transactions service, where losing an event actually matters a lot more
     * than it does for a welcome notification.
     */
    private void publishAfterCommit(AccountCreatedEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            accountEventPublisher.publishAccountCreated(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                accountEventPublisher.publishAccountCreated(event);
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        return accountMapper.toResponse(findAccountOrThrow(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccountByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Account", accountNumber));
        return accountMapper.toResponse(account);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listAccountsByOwner(UUID ownerId, KeysetPage page) {
        List<Account> fetched = page.isFirstPage()
                ? accountRepository.findFirstPageByOwnerId(ownerId, page.limitOnly())
                : accountRepository.findPageByOwnerIdAfter(
                        ownerId, page.afterCreatedAt(), page.afterId(), page.limitOnly());

        return page.build(fetched, accountMapper::toResponse, Account::getCreatedAt, Account::getId);
    }

    /**
     * The balance is summed by the database over every entry, while only one
     * page of entries is fetched. Those are two different questions and they
     * need two different queries: "does this account reconcile" is about the
     * whole ledger, "what were the last twenty movements" is about a page.
     * Answering the first from the second is what breaks the moment an account
     * outgrows a single response.
     * <p>
     * Both run in the same read-only transaction, so the page and the total
     * are read from one consistent snapshot rather than from two moments with
     * a write in between.
     */
    @Override
    @Transactional(readOnly = true)
    public LedgerResponse getLedger(UUID accountId, KeysetPage page) {
        Account account = findAccountOrThrow(accountId);

        List<LedgerEntry> fetched = page.isFirstPage()
                ? ledgerEntryRepository.findFirstPageByAccountId(accountId, page.limitOnly())
                : ledgerEntryRepository.findPageByAccountIdAfter(
                        accountId, page.afterCreatedAt(), page.afterId(), page.limitOnly());

        // Null, not zero, when the account has no entries at all - an account
        // opened at zero is a real state, and SUM over no rows is null in SQL.
        // The fallback carries the same scale the column does, so an empty
        // ledger reports "0.00" like every other amount in the API rather than
        // a bare "0".
        BigDecimal computedBalance = Optional.ofNullable(
                        ledgerEntryRepository.sumSignedAmountByAccountId(accountId, LedgerDirection.CREDIT))
                .orElse(ZERO_BALANCE);

        return ledgerMapper.toLedgerResponse(account, computedBalance,
                page.build(fetched, ledgerMapper::toResponse, LedgerEntry::getCreatedAt, LedgerEntry::getId));
    }

    @Override
    public AccountResponse freeze(UUID accountId) {
        return changeStatus(accountId, Account::freeze);
    }

    @Override
    public AccountResponse reactivate(UUID accountId) {
        return changeStatus(accountId, Account::reactivate);
    }

    @Override
    public AccountResponse close(UUID accountId) {
        return changeStatus(accountId, Account::close);
    }

    /**
     * No ledger entry and no idempotency key, unlike credit/debit: a status
     * change moves no money, so there is nothing for the ledger to explain,
     * and repeating one is harmless because it asks for a state rather than
     * for a delta.
     * <p>
     * It still contends on {@code @Version} like any other write, so it goes
     * through the same retry template - each attempt re-reads the account so
     * it decides against current state, and an exhausted budget surfaces as
     * a 409 for the caller to retry rather than as a 500.
     */
    private AccountResponse changeStatus(UUID accountId, Consumer<Account> transition) {
        RetryCallback<AccountResponse, RuntimeException> attempt = context ->
                transactionTemplate.execute(status -> {
                    Account account = findAccountOrThrow(accountId);
                    transition.accept(account);
                    return accountMapper.toResponse(accountRepository.save(account));
                });

        try {
            return optimisticLockRetryTemplate.execute(attempt);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrentUpdateException(
                    "Account " + accountId + " was modified concurrently too many times; please retry the request");
        }
    }

    @Override
    public AccountResponse credit(UUID accountId, String operationKey, BigDecimal amount) {
        return applyIdempotently(accountId, operationKey, LedgerDirection.CREDIT, amount);
    }

    @Override
    public AccountResponse debit(UUID accountId, String operationKey, BigDecimal amount) {
        return applyIdempotently(accountId, operationKey, LedgerDirection.DEBIT, amount);
    }

    /**
     * Applies one balance change exactly once, no matter how many times it's
     * called or how many callers race for it.
     * <p>
     * Two different hazards are in play here, and they need different answers:
     * <ul>
     *   <li><b>Two different operations on the same account.</b> They both
     *       read the same {@code version}, one commits, the other's UPDATE
     *       matches zero rows. That one is safe to redo, so the retry template
     *       runs it again in a <em>new</em> transaction against the winner's
     *       state. Both operations end up applied.</li>
     *   <li><b>The same operation twice</b> (a client retry, or a network
     *       failure that hid a response which had actually committed). This
     *       one must <em>not</em> be redone. The ledger's unique constraint on
     *       {@code (account_id, operation_key)} is what stops it, and the
     *       loser replays the winner's outcome instead of moving money
     *       again.</li>
     * </ul>
     * The in-transaction lookup below handles the common case where the first
     * call has long since committed. The constraint violation handles the
     * genuinely concurrent case, where both callers look, both find nothing,
     * and both try to insert - only the database can arbitrate that, so we let
     * it, and treat losing as success rather than as an error.
     */
    private AccountResponse applyIdempotently(UUID accountId, String operationKey,
                                               LedgerDirection direction, BigDecimal amount) {
        requireOperationKey(operationKey);

        RetryCallback<AccountResponse, RuntimeException> attempt = context ->
                transactionTemplate.execute(status -> {
                    Optional<LedgerEntry> alreadyPosted =
                            ledgerEntryRepository.findByAccountIdAndOperationKey(accountId, operationKey);
                    if (alreadyPosted.isPresent()) {
                        return replay(alreadyPosted.get(), accountId, direction, amount);
                    }

                    Account account = findAccountOrThrow(accountId);
                    apply(account, direction, amount);
                    Account saved = accountRepository.save(account);

                    // saveAndFlush, not save: this pushes the INSERT (and the
                    // pending account UPDATE) to the database now, so a
                    // duplicate key or a lost version race surfaces here as a
                    // catchable exception instead of at commit time, after
                    // this callback has already returned a response.
                    ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                            accountId, operationKey, direction, amount,
                            saved.getCurrency(), saved.getBalance()));

                    return accountMapper.toResponse(saved);
                });

        try {
            return optimisticLockRetryTemplate.execute(attempt);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrentUpdateException(
                    "Account " + accountId + " was modified concurrently too many times; please retry the request");
        } catch (DataIntegrityViolationException duplicateOperation) {
            // A concurrent request with the same operation key committed
            // between our lookup and our insert. That's the guarantee working,
            // not a failure: adopt its result.
            return transactionTemplate.execute(status -> {
                LedgerEntry winner = ledgerEntryRepository
                        .findByAccountIdAndOperationKey(accountId, operationKey)
                        // No entry means the violation came from some other
                        // constraint, so it's a real error - don't swallow it.
                        .orElseThrow(() -> duplicateOperation);
                return replay(winner, accountId, direction, amount);
            });
        }
    }

    /**
     * Returns the account as it stands now, having confirmed that
     * {@code posted} really is the operation being asked for again.
     * <p>
     * Note what this deliberately does not promise: a byte-identical copy of
     * the original response. Later operations may have moved the balance since,
     * and reporting a stale balance would be worse than reporting a current
     * one. The guarantee is that the operation was applied exactly once - a
     * caller who needs the balance as of that specific posting can read
     * {@code balanceAfter} from the ledger.
     */
    private AccountResponse replay(LedgerEntry posted, UUID accountId,
                                    LedgerDirection direction, BigDecimal amount) {
        if (!posted.matches(direction, amount)) {
            throw new OperationKeyReusedException(posted.getOperationKey());
        }
        return accountMapper.toResponse(findAccountOrThrow(accountId));
    }

    private void apply(Account account, LedgerDirection direction, BigDecimal amount) {
        switch (direction) {
            case CREDIT -> account.credit(amount);
            case DEBIT -> account.debit(amount);
        }
    }

    private static void requireOperationKey(String operationKey) {
        if (operationKey == null || operationKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (operationKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }

    private Account findAccountOrThrow(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Account", accountId));
    }

    private String generateUniqueAccountNumber() {
        for (int i = 0; i < MAX_ACCOUNT_NUMBER_ATTEMPTS; i++) {
            String candidate = randomDigits(ACCOUNT_NUMBER_LENGTH);
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Failed to generate a unique account number after " + MAX_ACCOUNT_NUMBER_ATTEMPTS + " attempts");
    }

    private String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }
}
