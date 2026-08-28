package com.portfolio.banking.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.banking.transaction.client.IAccountClient;
import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.exception.IdempotencyKeyReusedException;
import com.portfolio.banking.transaction.exception.InsufficientFundsException;
import com.portfolio.banking.transaction.exception.ResourceNotFoundException;
import com.portfolio.banking.transaction.mapper.TransactionMapper;
import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.model.Transaction;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
import com.portfolio.banking.transaction.repository.ITransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private ITransactionRepository transactionRepository;

    @Mock
    private IOutboxEventRepository outboxEventRepository;

    @Mock
    private IAccountClient accountClient;

    private TransferService transferService;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("50.00");
    private final TransferRequest request = new TransferRequest(sourceId, destinationId, amount, "USD");

    @BeforeEach
    void setUp() {
        var transactionMapper = new TransactionMapper();
        var objectMapper = new ObjectMapper();

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(1, Map.of())); // no retries needed for these tests
        retryTemplate.setBackOffPolicy(new NoBackOffPolicy());

        lenient().when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        transferService = new TransferService(
                transactionRepository, outboxEventRepository, transactionMapper,
                accountClient, retryTemplate, transactionTemplate, objectMapper);
    }

    @Test
    void transfer_happyPath_completesAndWritesOutboxEvent() {
        when(transactionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        TransferResponse response = transferService.transfer("key-1", request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountClient).debit(sourceId, amount);
        verify(accountClient).credit(destinationId, amount);
        verify(accountClient, never()).credit(eq(sourceId), any());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("transfer.completed");
    }

    @Test
    void transfer_insufficientFunds_failsWithoutCreditOrOutboxEvent() {
        when(transactionRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        doThrow(new InsufficientFundsException("not enough balance"))
                .when(accountClient).debit(sourceId, amount);

        TransferResponse response = transferService.transfer("key-2", request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).contains("not enough balance");
        verify(accountClient, never()).credit(any(), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void transfer_creditFails_compensatesAndMarksFailed() {
        when(transactionRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.empty());
        doNothing().when(accountClient).debit(sourceId, amount);
        doThrow(new ResourceNotFoundException("destination account not found"))
                .when(accountClient).credit(destinationId, amount);
        doNothing().when(accountClient).credit(sourceId, amount); // the compensation call succeeds

        TransferResponse response = transferService.transfer("key-3", request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureReason()).contains("compensated");
        verify(accountClient).credit(destinationId, amount);
        verify(accountClient).credit(sourceId, amount);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("transfer.failed");
    }

    @Test
    void transfer_creditFailsAndCompensationFails_marksCompensationFailed() {
        when(transactionRepository.findByIdempotencyKey("key-4")).thenReturn(Optional.empty());
        doNothing().when(accountClient).debit(sourceId, amount);
        doThrow(new ResourceNotFoundException("destination account not found"))
                .when(accountClient).credit(destinationId, amount);
        doThrow(new ResourceNotFoundException("source account vanished too"))
                .when(accountClient).credit(sourceId, amount);

        TransferResponse response = transferService.transfer("key-4", request);

        assertThat(response.status()).isEqualTo("COMPENSATION_FAILED");
        assertThat(response.failureReason()).contains("manual intervention required");
    }

    @Test
    void transfer_replayOfCompletedTransaction_doesNotCallAccountClientAgain() {
        Transaction alreadyCompleted = new Transaction("key-5", sourceId, destinationId, amount, "USD");
        alreadyCompleted.markCompleted();
        when(transactionRepository.findByIdempotencyKey("key-5")).thenReturn(Optional.of(alreadyCompleted));

        TransferResponse response = transferService.transfer("key-5", request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountClient, never()).debit(any(), any());
        verify(accountClient, never()).credit(any(), any());
    }

    @Test
    void transfer_sameKeyDifferentPayload_throwsIdempotencyKeyReusedException() {
        Transaction existingWithDifferentAmount =
                new Transaction("key-6", sourceId, destinationId, new BigDecimal("999.00"), "USD");
        when(transactionRepository.findByIdempotencyKey("key-6")).thenReturn(Optional.of(existingWithDifferentAmount));

        assertThatThrownBy(() -> transferService.transfer("key-6", request))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void transfer_resumesFromDebitedState_skipsDebitStep() {
        // Simulates a previous attempt that crashed after the debit committed
        // but before the saga finished - the row is stuck in DEBITED.
        Transaction debited = new Transaction("key-7", sourceId, destinationId, amount, "USD");
        debited.markDebited();
        when(transactionRepository.findByIdempotencyKey("key-7")).thenReturn(Optional.of(debited));

        TransferResponse response = transferService.transfer("key-7", request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        verify(accountClient, never()).debit(any(), any());
        verify(accountClient).credit(destinationId, amount);
    }

    @Test
    void transfer_sourceEqualsDestination_throwsIllegalArgumentException() {
        TransferRequest selfTransfer = new TransferRequest(sourceId, sourceId, amount, "USD");

        assertThatThrownBy(() -> transferService.transfer("key-8", selfTransfer))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
