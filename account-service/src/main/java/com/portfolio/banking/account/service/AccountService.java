package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.exception.ConcurrentUpdateException;
import com.portfolio.banking.account.exception.ResourceNotFoundException;
import com.portfolio.banking.account.mapper.IAccountMapper;
import com.portfolio.banking.account.messaging.IAccountEventPublisher;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.repository.IAccountRepository;
import com.portfolio.banking.common.event.AccountCreatedEvent;
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
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AccountService implements IAccountService {

    private static final int ACCOUNT_NUMBER_LENGTH = 12;
    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;

    private final IAccountRepository accountRepository;
    private final IAccountMapper accountMapper;
    private final IAccountEventPublisher accountEventPublisher;
    private final RetryTemplate optimisticLockRetryTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountService(IAccountRepository accountRepository,
                           IAccountMapper accountMapper,
                           IAccountEventPublisher accountEventPublisher,
                           RetryTemplate optimisticLockRetryTemplate,
                           TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
        this.accountEventPublisher = accountEventPublisher;
        this.optimisticLockRetryTemplate = optimisticLockRetryTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(
                generateUniqueAccountNumber(),
                request.ownerId(),
                request.openingBalance(),
                request.currency()
        );
        Account saved = accountRepository.save(account);
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
     * separate poller/relay publish it to RabbitMQ afterwards. We're
     * introducing that in the Transactions service, where losing an event
     * actually matters a lot more than it does for a welcome notification.
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
    public List<AccountResponse> listAccountsByOwner(UUID ownerId) {
        return accountRepository.findAllByOwnerId(ownerId).stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    @Override
    public AccountResponse credit(UUID accountId, BigDecimal amount) {
        return applyWithRetry(accountId, account -> account.credit(amount));
    }

    @Override
    public AccountResponse debit(UUID accountId, BigDecimal amount) {
        return applyWithRetry(accountId, account -> account.debit(amount));
    }

    /**
     * Runs {@code mutation} against the account inside its own transaction,
     * and retries the whole read-mutate-write cycle if the write loses the
     * optimistic-locking race. Each retry attempt is a brand new transaction,
     * so it re-reads the account with whatever version won the previous race
     * - it is not simply re-trying a stale write.
     */
    private AccountResponse applyWithRetry(UUID accountId, Consumer<Account> mutation) {
        RetryCallback<AccountResponse, RuntimeException> attempt = context ->
                transactionTemplate.execute(status -> {
                    Account account = findAccountOrThrow(accountId);
                    mutation.accept(account);
                    Account saved = accountRepository.save(account);
                    return accountMapper.toResponse(saved);
                });

        try {
            return optimisticLockRetryTemplate.execute(attempt);
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ConcurrentUpdateException(
                    "Account " + accountId + " was modified concurrently too many times; please retry the request");
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
