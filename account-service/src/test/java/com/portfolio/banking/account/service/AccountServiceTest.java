package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.exception.ConcurrentUpdateException;
import com.portfolio.banking.account.exception.InsufficientFundsException;
import com.portfolio.banking.account.exception.ResourceNotFoundException;
import com.portfolio.banking.account.mapper.AccountMapper;
import com.portfolio.banking.account.messaging.IAccountEventPublisher;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.repository.IAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private IAccountRepository accountRepository;

    @Mock
    private IAccountEventPublisher accountEventPublisher;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        // Real mapper: it's pure data shuffling, mocking it would just
        // duplicate the mapping logic in every test's stubbing.
        var accountMapper = new AccountMapper();

        // Real TransactionTemplate backed by a mocked PlatformTransactionManager:
        // getTransaction/commit/rollback are no-ops, but the callback still runs
        // for real, so retry behavior is exercised exactly as in production.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                3, Map.of(ObjectOptimisticLockingFailureException.class, true)));
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy()); // don't slow tests down

        accountService = new AccountService(
                accountRepository, accountMapper, accountEventPublisher, retryTemplate, transactionTemplate);
    }

    @Test
    void createAccount_persistsAndReturnsAccount() {
        UUID ownerId = UUID.randomUUID();
        CreateAccountRequest request = new CreateAccountRequest(ownerId, new BigDecimal("100.00"), "USD");
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.ownerId()).isEqualTo(ownerId);
        assertThat(response.balance()).isEqualByComparingTo("100.00");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.accountNumber()).hasSize(12);
    }

    @Test
    void getAccount_whenMissing_throwsResourceNotFoundException() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(accountId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void credit_increasesBalance() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account("123456789012", UUID.randomUUID(), new BigDecimal("50.00"), "USD");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.credit(accountId, new BigDecimal("25.00"));

        assertThat(response.balance()).isEqualByComparingTo("75.00");
        verify(accountRepository, times(1)).save(any(Account.class));
    }

    @Test
    void debit_withInsufficientFunds_throwsAndNeverSaves() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account("123456789012", UUID.randomUUID(), new BigDecimal("10.00"), "USD");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.debit(accountId, new BigDecimal("50.00")))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository, times(0)).save(any(Account.class));
    }

    @Test
    void debit_onOptimisticLockConflict_retriesAndSucceeds() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        // Each retry attempt must re-read the account, exactly like a fresh
        // transaction re-reading the row would. Returning a brand new Account
        // instance per call (instead of the same mutated one) is what makes
        // this test actually exercise "retry re-reads the true committed
        // state" rather than "retry re-mutates whatever is already in memory".
        when(accountRepository.findById(accountId)).thenAnswer(inv ->
                Optional.of(new Account("123456789012", ownerId, new BigDecimal("100.00"), "USD")));
        // First save attempt loses the optimistic-locking race; second succeeds.
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId))
                .thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.debit(accountId, new BigDecimal("30.00"));

        assertThat(response.balance()).isEqualByComparingTo("70.00");
        verify(accountRepository, times(2)).findById(accountId);
        verify(accountRepository, times(2)).save(any(Account.class));
    }

    @Test
    void debit_whenRetriesExhausted_throwsConcurrentUpdateException() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account("123456789012", UUID.randomUUID(), new BigDecimal("100.00"), "USD");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, accountId));

        assertThatThrownBy(() -> accountService.debit(accountId, new BigDecimal("30.00")))
                .isInstanceOf(ConcurrentUpdateException.class);

        verify(accountRepository, times(3)).save(any(Account.class));
    }
}
