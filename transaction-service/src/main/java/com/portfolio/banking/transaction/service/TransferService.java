package com.portfolio.banking.transaction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.transaction.client.IAccountClient;
import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.exception.IdempotencyKeyReusedException;
import com.portfolio.banking.transaction.exception.ResourceNotFoundException;
import com.portfolio.banking.transaction.mapper.ITransactionMapper;
import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.model.Transaction;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
import com.portfolio.banking.transaction.repository.ITransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orchestrates a transfer as a saga with one compensating action, using the
 * outbox pattern to publish its outcome reliably.
 * <p>
 * Deliberately NOT one big {@code @Transactional} method: it makes two
 * network calls to account-service, and a database transaction must never
 * stay open across a network call to another service (it would hold a
 * connection - and possibly row locks - for however long that call takes,
 * including its retries). Instead, each database write is its own short
 * transaction (via {@code transactionTemplate}), with the REST calls
 * happening in between, in this un-transactional orchestrating method.
 * <p>
 * Idempotency runs at two levels, and both are needed. At the top,
 * the client's {@code Idempotency-Key} stops a resubmitted transfer from
 * running the saga twice. Underneath, each leg sends account-service its own
 * derived key (see {@link #operationKeyFor}), so that a retry of a single
 * debit or credit - by the retry template here, or by a new process after this
 * one crashed - can't move money twice either. Without the second level, the
 * first only narrows the window rather than closing it: a debit call that
 * timed out after committing looks identical to one that never arrived.
 */
@Service
public class TransferService implements ITransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String AGGREGATE_TYPE = "Transaction";
    private static final String ROUTING_KEY_COMPLETED = "transfer.completed";
    private static final String ROUTING_KEY_FAILED = "transfer.failed";

    /** Suffixes distinguishing the idempotency keys of a saga's three legs. */
    private static final String LEG_DEBIT = "debit";
    private static final String LEG_CREDIT = "credit";
    private static final String LEG_COMPENSATION = "compensation";

    private final ITransactionRepository transactionRepository;
    private final IOutboxEventRepository outboxEventRepository;
    private final ITransactionMapper transactionMapper;
    private final IAccountClient accountClient;
    private final RetryTemplate remoteCallRetryTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public TransferService(ITransactionRepository transactionRepository,
                            IOutboxEventRepository outboxEventRepository,
                            ITransactionMapper transactionMapper,
                            IAccountClient accountClient,
                            RetryTemplate remoteCallRetryTemplate,
                            TransactionTemplate transactionTemplate,
                            ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.transactionMapper = transactionMapper;
        this.accountClient = accountClient;
        this.remoteCallRetryTemplate = remoteCallRetryTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new IllegalArgumentException("sourceAccountId and destinationAccountId must differ");
        }

        Transaction transaction = findOrCreate(idempotencyKey, request);

        if (!transaction.matches(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.currency())) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }

        return switch (transaction.getStatus()) {
            case PENDING -> runFullSaga(transaction);
            // A previous attempt crashed after the debit committed but before
            // the saga finished. The debit already happened - resume from the
            // credit step instead of starting over (which would debit twice).
            case DEBITED -> runCreditAndFinish(transaction);
            case COMPLETED, FAILED, COMPENSATION_FAILED -> transactionMapper.toResponse(transaction);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public TransferResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> ResourceNotFoundException.forEntity("Transaction", transactionId));
        return transactionMapper.toResponse(transaction);
    }

    private Transaction findOrCreate(String idempotencyKey, TransferRequest request) {
        return transactionTemplate.execute(status -> {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            Transaction created = new Transaction(idempotencyKey, request.sourceAccountId(),
                    request.destinationAccountId(), request.amount(), request.currency());
            try {
                return transactionRepository.save(created);
            } catch (DataIntegrityViolationException raceLost) {
                // Another concurrent request with the same key won the insert
                // race. Not an error - fetch what it created and proceed as
                // if we'd found it in the first place.
                return transactionRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> raceLost);
            }
        });
    }

    /**
     * Explicitly typed as {@code RetryCallback<Void, RuntimeException>}
     * rather than passing an inline lambda straight to {@code execute(...)}.
     * Without an assignment target, javac can't infer the exception type
     * parameter E (bounded only by {@code extends Throwable}) from an
     * unconstrained lambda, and defaults it to {@code Throwable} itself -
     * which then doesn't compile against a {@code catch (RuntimeException ...)}
     * around the call. Typing the callback explicitly pins E to
     * {@code RuntimeException}, which is all our code ever throws here.
     */
    private void debitWithRetry(UUID accountId, String operationKey, BigDecimal amount) {
        RetryCallback<Void, RuntimeException> callback = ctx -> {
            accountClient.debit(accountId, operationKey, amount);
            return null;
        };
        remoteCallRetryTemplate.execute(callback);
    }

    private void creditWithRetry(UUID accountId, String operationKey, BigDecimal amount) {
        RetryCallback<Void, RuntimeException> callback = ctx -> {
            accountClient.credit(accountId, operationKey, amount);
            return null;
        };
        remoteCallRetryTemplate.execute(callback);
    }

    /**
     * Derives this leg's idempotency key from the transaction id.
     * <p>
     * Deriving rather than generating is the whole point: a key must be
     * <em>stable</em> across every attempt at the same leg, including attempts
     * made by a different process after this one crashed. A freshly generated
     * UUID would be unique per attempt, which is precisely the property that
     * makes double-spending possible. The transaction id is already durable in
     * our own database, so any retry - now or after a restart - recomputes the
     * identical key.
     * <p>
     * The suffix matters for the compensation leg specifically: it credits the
     * same account the debit came from, so it needs a key of its own. The other
     * two legs touch different accounts and account-service scopes keys per
     * account, but naming them explicitly keeps the ledger readable.
     */
    private static String operationKeyFor(Transaction transaction, String leg) {
        return transaction.getId() + ":" + leg;
    }

    private TransferResponse runFullSaga(Transaction transaction) {
        try {
            debitWithRetry(transaction.getSourceAccountId(),
                    operationKeyFor(transaction, LEG_DEBIT), transaction.getAmount());
        } catch (RuntimeException debitFailure) {
            // Nothing external happened (the debit itself never took effect),
            // so there's nothing to compensate and nothing worth telling a
            // downstream consumer about - no outbox event for this case.
            log.info("Transfer {} failed at debit step: {}", transaction.getId(), debitFailure.getMessage());
            markFailedNoOutbox(transaction, debitFailure.getMessage());
            return transactionMapper.toResponse(transaction);
        }

        markDebited(transaction);
        return runCreditAndFinish(transaction);
    }

    private TransferResponse runCreditAndFinish(Transaction transaction) {
        try {
            creditWithRetry(transaction.getDestinationAccountId(),
                    operationKeyFor(transaction, LEG_CREDIT), transaction.getAmount());
        } catch (RuntimeException creditFailure) {
            return compensateAndMarkFailed(transaction, creditFailure);
        }

        markCompletedWithOutbox(transaction);
        return transactionMapper.toResponse(transaction);
    }

    private TransferResponse compensateAndMarkFailed(Transaction transaction, RuntimeException creditFailure) {
        log.warn("Transfer {} failed at credit step ({}); compensating by crediting source {} back",
                transaction.getId(), creditFailure.getMessage(), transaction.getSourceAccountId());
        try {
            creditWithRetry(transaction.getSourceAccountId(),
                    operationKeyFor(transaction, LEG_COMPENSATION), transaction.getAmount());
        } catch (RuntimeException compensationFailure) {
            // The worst case: money is stuck mid-transfer. In a real system
            // this should page someone, not just sit in COMPENSATION_FAILED.
            log.error("Transfer {} compensation FAILED - manual intervention required. "
                            + "Credit failure: {}. Compensation failure: {}",
                    transaction.getId(), creditFailure.getMessage(), compensationFailure.getMessage());
            String reason = "Credit to destination failed (" + creditFailure.getMessage()
                    + ") and compensation also failed (" + compensationFailure.getMessage()
                    + "); manual intervention required";
            markCompensationFailedWithOutbox(transaction, reason);
            return transactionMapper.toResponse(transaction);
        }

        markFailedWithOutbox(transaction,
                "Credit to destination failed and was compensated: " + creditFailure.getMessage());
        return transactionMapper.toResponse(transaction);
    }

    private void markDebited(Transaction transaction) {
        transactionTemplate.executeWithoutResult(status -> {
            transaction.markDebited();
            transactionRepository.save(transaction);
        });
    }

    private void markFailedNoOutbox(Transaction transaction, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            transaction.markFailed(reason);
            transactionRepository.save(transaction);
        });
    }

    private void markFailedWithOutbox(Transaction transaction, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            transaction.markFailed(reason);
            transactionRepository.save(transaction);
            writeOutboxEvent(transaction, ROUTING_KEY_FAILED, buildFailedEvent(transaction, reason));
        });
    }

    private void markCompensationFailedWithOutbox(Transaction transaction, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            transaction.markCompensationFailed(reason);
            transactionRepository.save(transaction);
            writeOutboxEvent(transaction, ROUTING_KEY_FAILED, buildFailedEvent(transaction, reason));
        });
    }

    private void markCompletedWithOutbox(Transaction transaction) {
        transactionTemplate.executeWithoutResult(status -> {
            transaction.markCompleted();
            transactionRepository.save(transaction);
            writeOutboxEvent(transaction, ROUTING_KEY_COMPLETED, buildCompletedEvent(transaction));
        });
    }

    private TransferCompletedEvent buildCompletedEvent(Transaction transaction) {
        return new TransferCompletedEvent(
                transaction.getId(), transaction.getSourceAccountId(), transaction.getDestinationAccountId(),
                transaction.getAmount(), transaction.getCurrency());
    }

    private TransferFailedEvent buildFailedEvent(Transaction transaction, String reason) {
        return new TransferFailedEvent(
                transaction.getId(), transaction.getSourceAccountId(), transaction.getDestinationAccountId(),
                transaction.getAmount(), transaction.getCurrency(), reason);
    }

    /** Must be called from inside an active transactionTemplate block (it saves the row itself). */
    private void writeOutboxEvent(Transaction transaction, String routingKey, Object eventPayload) {
        try {
            String json = objectMapper.writeValueAsString(eventPayload);
            outboxEventRepository.save(new OutboxEvent(AGGREGATE_TYPE, transaction.getId(), routingKey, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload for transaction "
                    + transaction.getId(), e);
        }
    }
}
