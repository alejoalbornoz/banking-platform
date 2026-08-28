package com.portfolio.banking.transaction.messaging;

import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
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
import java.util.List;

/**
 * The second half of the outbox pattern: {@code TransferService} guarantees
 * an event row exists for every state change (atomically, in the same local
 * transaction as the state change itself). This relay's only job is to
 * notice unpublished rows and get them onto RabbitMQ, retrying on every poll
 * until it succeeds.
 * <p>
 * This is a simple polling implementation - fine for a portfolio project and
 * for modest throughput. A production system at scale would more likely use
 * change-data-capture (e.g. Debezium tailing the WAL) instead of polling, to
 * get near-real-time publishing without hammering the table with SELECTs.
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

    private static final int BATCH_SIZE = 50;

    private final IOutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String exchangeName;

    public OutboxRelay(IOutboxEventRepository outboxEventRepository,
                        RabbitTemplate rabbitTemplate,
                        TransactionTemplate transactionTemplate,
                        @Value("${banking.rabbitmq.exchange}") String exchangeName) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.exchangeName = exchangeName;
    }

    @Scheduled(fixedDelayString = "${banking.outbox.relay-interval-ms:2000}")
    public void relayPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(
                PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    /**
     * Publishes and marks-published in one local transaction, per event.
     * If RabbitMQ is unreachable, the publish throws, the transaction rolls
     * back, the row stays unpublished, and the next poll tries again - so a
     * broker outage delays delivery but never loses the event.
     */
    private void publishOne(OutboxEvent event) {
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
    }
}
