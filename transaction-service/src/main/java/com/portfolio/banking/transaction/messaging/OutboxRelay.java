package com.portfolio.banking.transaction.messaging;

import com.portfolio.banking.transaction.alert.IDeadLetteredEventAlerter;
import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The second half of the outbox pattern: {@code TransferService} guarantees
 * an event row exists for every state change (atomically, in the same local
 * transaction as the state change itself). This relay's only job is to notice
 * unpublished rows and get them onto RabbitMQ.
 * <p>
 * <b>Two failures are not the same failure, and treating them alike breaks
 * one of them.</b> A broker that is down is not the row's fault: every event
 * is equally unpublishable, waiting is the correct response, and counting
 * that against individual rows would turn a fifteen-minute outage into
 * permanent data loss - the relay polls every two seconds, so any sane
 * attempt budget would be spent long before RabbitMQ came back, and the whole
 * backlog would be dead-lettered. So a connection-level failure stops the
 * cycle without advancing anything: nothing behind this event was going to
 * fare better, and the next poll will find the same work.
 * <p>
 * A failure that is about <em>this message</em> - a payload the broker
 * refuses, a conversion that cannot succeed - is the opposite. Waiting will
 * not fix it, and retrying it forever is not patience but a stall: the poll
 * is ordered oldest-first, so a hopeless event sits at the head of every
 * batch with the entire outbox queued behind it. Those get a bounded number
 * of attempts and are then parked and reported.
 * <p>
 * The per-event {@code try} matters as much as the classification. Without
 * it, one throwing event aborts the whole batch, which is the same
 * head-of-line stall by a different route.
 * <p>
 * This is a simple polling implementation - fine for a portfolio project and
 * for modest throughput. A production system at scale would more likely use
 * change-data-capture (e.g. Debezium tailing the WAL) instead of polling, to
 * get near-real-time publishing without hammering the table with SELECTs. It
 * also assumes a single relay: two instances would poll the same rows, which
 * wants {@code FOR UPDATE SKIP LOCKED} on the claim rather than anything in
 * this class.
 * <p>
 * Note on transactions: {@code publishOne} uses {@code transactionTemplate}
 * explicitly rather than {@code @Transactional}, deliberately. It's called
 * from {@code relayPendingEvents} in this same class - a plain {@code this.}
 * call - and Spring's {@code @Transactional} only works through the proxy
 * that wraps external calls into a bean. A same-class call bypasses that
 * proxy entirely, so the annotation would silently do nothing.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ERROR_LENGTH = 500;

    private final IOutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final IDeadLetteredEventAlerter deadLetteredEventAlerter;
    private final String exchangeName;
    private final int maxPublishAttempts;

    public OutboxRelay(IOutboxEventRepository outboxEventRepository,
                        RabbitTemplate rabbitTemplate,
                        TransactionTemplate transactionTemplate,
                        IDeadLetteredEventAlerter deadLetteredEventAlerter,
                        @Value("${banking.rabbitmq.exchange}") String exchangeName,
                        @Value("${banking.outbox.max-publish-attempts}") int maxPublishAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.deadLetteredEventAlerter = deadLetteredEventAlerter;
        this.exchangeName = exchangeName;
        this.maxPublishAttempts = maxPublishAttempts;
    }

    @Scheduled(fixedDelayString = "${banking.outbox.relay-interval-ms:2000}")
    public void relayPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByPublishedFalseAndDeadAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : pending) {
            if (!publishOne(event)) {
                // The broker is unreachable. Everything behind this row is in
                // exactly the same position, so trying them would only
                // produce the same exception fifty more times.
                return;
            }
        }
    }

    /**
     * Publishes and marks-published in one local transaction. If the publish
     * throws, that transaction rolls back and the row stays unpublished, so
     * the worst case is publishing twice - which the consumer already
     * deduplicates on {@code eventId}.
     *
     * @return {@code false} if the broker itself is unreachable and this
     *         cycle should stop; {@code true} if the relay should carry on
     *         with the rest of the batch, whether or not this event succeeded
     */
    private boolean publishOne(OutboxEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // eventType doubles as the routing key (e.g. "transfer.completed"),
                // set by TransferService when it writes the row.
                Message message = MessageBuilder
                        .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build();
                rabbitTemplate.send(exchangeName, event.getEventType(), message);

                event.markPublished();
                outboxEventRepository.save(event);
            });
            return true;
        } catch (AmqpConnectException | AmqpIOException | AmqpTimeoutException brokerUnreachable) {
            // Not this event's fault, so it does not cost this event an
            // attempt. Logged at WARN rather than ERROR: nothing is lost, the
            // backlog simply waits, and an outage that logs an ERROR every
            // two seconds trains people to ignore ERRORs.
            log.warn("Outbox relay paused - the broker is unreachable ({}). {} event(s) are waiting.",
                    brokerUnreachable.getMessage(),
                    outboxEventRepository.countByPublishedFalseAndDeadAtIsNull());
            return false;
        } catch (RuntimeException thisEventFailed) {
            recordFailure(event, thisEventFailed);
            return true;
        }
    }

    /**
     * Runs outside the rolled-back transaction above, in its own, so the
     * count survives the failure that produced it - a counter that rolls back
     * whenever it counts something never reaches any limit.
     */
    private void recordFailure(OutboxEvent event, RuntimeException failure) {
        String reason = truncate(String.valueOf(failure));
        int attempts = event.getAttempts() + 1;

        if (attempts >= maxPublishAttempts) {
            outboxEventRepository.recordFailureAndMarkDead(event.getId(), reason, Instant.now());
            alertQuietly(event, attempts, reason);
            return;
        }

        log.warn("Outbox event {} failed to publish (attempt {} of {}): {}",
                event.getId(), attempts, maxPublishAttempts, reason);
        outboxEventRepository.recordFailure(event.getId(), reason);
    }

    /**
     * The row is already dead by the time this runs, so a failure in the
     * alerting channel must not propagate - it would abort the batch and
     * re-introduce the stall this whole class exists to remove. Same ordering
     * and same reasoning as the stuck-transfer alert.
     */
    private void alertQuietly(OutboxEvent event, int attempts, String reason) {
        try {
            deadLetteredEventAlerter.alert(event, attempts, reason);
        } catch (RuntimeException alertingFailed) {
            log.error("Failed to raise the dead-letter alert for outbox event {} - it is dead-lettered "
                    + "regardless", event.getId(), alertingFailed);
        }
    }

    private static String truncate(String reason) {
        return reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
    }
}
