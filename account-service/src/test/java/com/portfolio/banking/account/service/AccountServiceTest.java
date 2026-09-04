package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.exception.ConcurrentUpdateException;
import com.portfolio.banking.account.exception.InsufficientFundsException;
import com.portfolio.banking.account.exception.OperationKeyReusedException;
import com.portfolio.banking.account.exception.ResourceNotFoundException;
import com.portfolio.banking.account.mapper.AccountMapper;
import com.portfolio.banking.account.mapper.LedgerMapper;
import com.portfolio.banking.account.messaging.IAccountEventPublisher;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.model.LedgerDirection;
import com.portfolio.banking.account.model.LedgerEntry;
import com.portfolio.banking.account.repository.IAccountRepository;
import com.portfolio.banking.account.repository.ILedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String OPERATION_KEY = "op-1";

    @Mock
    private IAccountRepository accountRepository;

    @Mock
    private ILedgerEntryRepository ledgerEntryRepository;

    @Mock
    private IAccountEventPublisher accountEventPublisher;

    private AccountService accountService;

    private final UUID accountId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real mappers: they're pure data shuffling, mocking them would just
        // duplicate the mapping logic in every test's stubbing.
        var accountMapper = new AccountMapper();
        var ledgerMapper = new LedgerMapper();

        // Real TransactionTemplate backed by a mocked PlatformTransactionManager:
        // getTransaction/commit/rollback are no-ops, but the callback still runs
        // for real, so retry behavior is exercised exactly as in production.
        //
        // lenient(): only credit/debit go through the TransactionTemplate. The
        // read/create paths are annotated @Transactional, which is a no-op here
        // (no Spring proxy in a plain unit test), so for those tests this stub
        // is never called and strict stubbing would fail them.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                3, Map.of(ObjectOptimisticLockingFailureException.class, true)));
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy()); // don't slow tests down

        accountService = new AccountService(
                accountRepository, ledgerEntryRepository, accountMapper, ledgerMapper,
                accountEventPublisher, retryTemplate, transactionTemplate);
    }

    private Account accountWith(String balance) {
        return new Account("123456789012", ownerId, new BigDecimal(balance), "USD");
    }

    /** No prior posting under this key - the operation is new. */
    private void givenOperationNotYetPosted() {
        when(ledgerEntryRepository.findByAccountIdAndOperationKey(accountId, OPERATION_KEY))
                .thenReturn(Optional.empty());
    }

    @Test
    void createAccount_persistsAndReturnsAccount() {
        CreateAccountRequest request = new CreateAccountRequest(new BigDecimal("100.00"), "USD");
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.createAccount(ownerId, request);

        assertThat(response.ownerId()).isEqualTo(ownerId);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.accountNumber()).hasSize(12);
    }

    @Test
    void createAccount_withOpeningBalance_recordsItInTheLedger() {
        CreateAccountRequest request = new CreateAccountRequest(new BigDecimal("100.00"), "USD");
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        accountService.createAccount(ownerId, request);

        // Without this entry the account's balance would have no explanation in
        // the ledger, and it would fail reconciliation from the moment it opened.
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry opening = captor.getValue();
        assertThat(opening.getOperationKey()).isEqualTo(LedgerEntry.OPENING_OPERATION_KEY);
        assertThat(opening.getDirection()).isEqualTo(LedgerDirection.CREDIT);
        assertThat(opening.getAmount()).isEqualByComparingTo("100.00");
        assertThat(opening.getBalanceAfter()).isEqualByComparingTo("100.00");
    }

    @Test
    void createAccount_withZeroOpeningBalance_recordsNoLedgerEntry() {
        CreateAccountRequest request = new CreateAccountRequest(BigDecimal.ZERO, "USD");
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        accountService.createAccount(ownerId, request);

        // An empty ledger already sums to zero, so there is nothing to record -
        // and a zero-amount entry would violate the amount > 0 constraint.
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void getAccount_whenMissing_throwsResourceNotFoundException() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(accountId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void credit_increasesBalanceAndPostsLedgerEntry() {
        givenOperationNotYetPosted();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("50.00")));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.credit(accountId, OPERATION_KEY, new BigDecimal("25.00"));

        assertThat(response.balance()).isEqualByComparingTo("75.00");
        verify(accountRepository, times(1)).save(any(Account.class));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).saveAndFlush(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getOperationKey()).isEqualTo(OPERATION_KEY);
        assertThat(entry.getDirection()).isEqualTo(LedgerDirection.CREDIT);
        assertThat(entry.getAmount()).isEqualByComparingTo("25.00");
        // The running balance is snapshotted on the entry, so a statement can
        // be read back without replaying every prior posting.
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("75.00");
    }

    @Test
    void credit_repeatedWithSameKey_appliesOnceAndReplaysResult() {
        // The first call committed long ago; this is a client retry.
        LedgerEntry alreadyPosted = new LedgerEntry(accountId, OPERATION_KEY, LedgerDirection.CREDIT,
                new BigDecimal("25.00"), "USD", new BigDecimal("75.00"));
        when(ledgerEntryRepository.findByAccountIdAndOperationKey(accountId, OPERATION_KEY))
                .thenReturn(Optional.of(alreadyPosted));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("75.00")));

        AccountResponse response = accountService.credit(accountId, OPERATION_KEY, new BigDecimal("25.00"));

        assertThat(response.balance()).isEqualByComparingTo("75.00");
        // The whole point: no second posting, and no second balance change.
        verify(accountRepository, never()).save(any(Account.class));
        verify(ledgerEntryRepository, never()).saveAndFlush(any());
    }

    @Test
    void credit_sameKeyDifferentAmount_throwsOperationKeyReusedException() {
        LedgerEntry postedForADifferentAmount = new LedgerEntry(accountId, OPERATION_KEY,
                LedgerDirection.CREDIT, new BigDecimal("25.00"), "USD", new BigDecimal("75.00"));
        when(ledgerEntryRepository.findByAccountIdAndOperationKey(accountId, OPERATION_KEY))
                .thenReturn(Optional.of(postedForADifferentAmount));

        // Replaying the original would silently do something other than what
        // this caller asked for, so we refuse instead of guessing.
        assertThatThrownBy(() ->
                accountService.credit(accountId, OPERATION_KEY, new BigDecimal("999.00")))
                .isInstanceOf(OperationKeyReusedException.class);

        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void credit_whenConcurrentDuplicateWinsTheInsert_adoptsItsResultInsteadOfDoubleCrediting() {
        // Two identical requests arrive at once. Both look up the key, both
        // find nothing, both try to post. Only the database can arbitrate that,
        // and this test is the loser's side of it.
        when(ledgerEntryRepository.findByAccountIdAndOperationKey(accountId, OPERATION_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new LedgerEntry(accountId, OPERATION_KEY, LedgerDirection.CREDIT,
                        new BigDecimal("25.00"), "USD", new BigDecimal("75.00"))));
        // Fresh instances per call: our own attempt rolls back, so the re-read
        // has to show the winner's committed state, not our mutated object.
        when(accountRepository.findById(accountId))
                .thenReturn(Optional.of(accountWith("50.00")))
                .thenReturn(Optional.of(accountWith("75.00")));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.saveAndFlush(any(LedgerEntry.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates "
                        + "unique constraint \"uq_ledger_entries_account_operation\""));

        AccountResponse response = accountService.credit(accountId, OPERATION_KEY, new BigDecimal("25.00"));

        // 75.00, not 100.00: the money moved once, and losing the race is
        // reported as success rather than as an error.
        assertThat(response.balance()).isEqualByComparingTo("75.00");
        verify(ledgerEntryRepository, times(1)).saveAndFlush(any(LedgerEntry.class));
    }

    @Test
    void credit_withBlankOperationKey_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> accountService.credit(accountId, "  ", new BigDecimal("25.00")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository, never()).findById(any());
    }

    @Test
    void debit_withInsufficientFunds_throwsAndPostsNothing() {
        givenOperationNotYetPosted();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("10.00")));

        assertThatThrownBy(() ->
                accountService.debit(accountId, OPERATION_KEY, new BigDecimal("50.00")))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository, never()).save(any(Account.class));
        verify(ledgerEntryRepository, never()).saveAndFlush(any());
    }

    @Test
    void debit_onOptimisticLockConflict_retriesAndSucceeds() {
        givenOperationNotYetPosted();
        // Each retry attempt must re-read the account, exactly like a fresh
        // transaction re-reading the row would. Returning a brand new Account
        // instance per call (instead of the same mutated one) is what makes
        // this test actually exercise "retry re-reads the true committed
        // state" rather than "retry re-mutates whatever is already in memory".
        when(accountRepository.findById(accountId)).thenAnswer(inv -> Optional.of(accountWith("100.00")));
        // First save attempt loses the optimistic-locking race; second succeeds.
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.debit(accountId, OPERATION_KEY, new BigDecimal("30.00"));

        assertThat(response.balance()).isEqualByComparingTo("70.00");
        verify(accountRepository, times(2)).findById(accountId);
        verify(accountRepository, times(2)).save(any(Account.class));
        // One posting despite two attempts: the losing attempt rolled back
        // before it ever reached the ledger.
        verify(ledgerEntryRepository, times(1)).saveAndFlush(any(LedgerEntry.class));
    }

    @Test
    void debit_whenRetriesExhausted_throwsConcurrentUpdateException() {
        givenOperationNotYetPosted();
        when(accountRepository.findById(accountId)).thenAnswer(inv -> Optional.of(accountWith("100.00")));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId));

        assertThatThrownBy(() ->
                accountService.debit(accountId, OPERATION_KEY, new BigDecimal("30.00")))
                .isInstanceOf(ConcurrentUpdateException.class);

        verify(accountRepository, times(3)).save(any(Account.class));
    }

    @Test
    void getLedger_reportsStoredAndRecomputedBalanceAsReconciled() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("75.00")));
        when(ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(
                new LedgerEntry(accountId, "op-2", LedgerDirection.DEBIT,
                        new BigDecimal("25.00"), "USD", new BigDecimal("75.00")),
                new LedgerEntry(accountId, LedgerEntry.OPENING_OPERATION_KEY, LedgerDirection.CREDIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("100.00"))));

        LedgerResponse ledger = accountService.getLedger(accountId);

        assertThat(ledger.storedBalance()).isEqualByComparingTo("75.00");
        assertThat(ledger.computedBalance()).isEqualByComparingTo("75.00");
        assertThat(ledger.reconciled()).isTrue();
        assertThat(ledger.entries()).hasSize(2);
    }

    @Test
    void getLedger_whenStoredBalanceDisagreesWithEntries_reportsNotReconciled() {
        // Should be impossible - the balance and its entry are written in one
        // transaction. Asserting it anyway means that if the invariant ever
        // does break, it surfaces as a visible flag rather than as quietly
        // wrong money.
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("999.00")));
        when(ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(accountId)).thenReturn(List.of(
                new LedgerEntry(accountId, LedgerEntry.OPENING_OPERATION_KEY, LedgerDirection.CREDIT,
                        new BigDecimal("100.00"), "USD", new BigDecimal("100.00"))));

        LedgerResponse ledger = accountService.getLedger(accountId);

        assertThat(ledger.reconciled()).isFalse();
        assertThat(ledger.storedBalance()).isEqualByComparingTo("999.00");
        assertThat(ledger.computedBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void ledgerEntries_sumToTheBalanceRegardlessOfDirection() {
        // Guards the sign convention on LedgerDirection: amounts are always
        // stored positive, so a bug there would show up as a debit that
        // increases the balance.
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(accountWith("30.00")));
        when(ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(eq(accountId))).thenReturn(List.of(
                new LedgerEntry(accountId, "d2", LedgerDirection.DEBIT,
                        new BigDecimal("40.00"), "USD", new BigDecimal("30.00")),
                new LedgerEntry(accountId, "c1", LedgerDirection.CREDIT,
                        new BigDecimal("20.00"), "USD", new BigDecimal("70.00")),
                new LedgerEntry(accountId, LedgerEntry.OPENING_OPERATION_KEY, LedgerDirection.CREDIT,
                        new BigDecimal("50.00"), "USD", new BigDecimal("50.00"))));

        LedgerResponse ledger = accountService.getLedger(accountId);

        assertThat(ledger.computedBalance()).isEqualByComparingTo("30.00");
        assertThat(ledger.reconciled()).isTrue();
    }
}
