package com.portfolio.banking.notification.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.notification.projection.IMovementProjector;
import com.portfolio.banking.notification.service.INotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dispatches by routing key rather than trusting a message-type header.
 * <p>
 * account-service publishes via {@code Jackson2JsonMessageConverter}, which
 * stamps a {@code __TypeId__} header naming its own Java class.
 * transaction-service's outbox relay deliberately sends pre-serialized JSON as
 * raw bytes with no such header (see its {@code OutboxRelay} - a converter
 * there would double-encode an already-serialized string). A consumer that
 * trusted that header would work for one publisher and break on the other.
 * Routing key is the one thing every publisher on this exchange reliably
 * sets, so that's what decides how to deserialize the body.
 */
@Component
public class BankingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BankingEventListener.class);

    private final INotificationService notificationService;
    private final IMovementProjector movementProjector;
    private final ObjectMapper objectMapper;

    public BankingEventListener(INotificationService notificationService,
                                 IMovementProjector movementProjector,
                                 ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.movementProjector = movementProjector;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${banking.rabbitmq.notification-queue}")
    public void onMessage(Message message) throws IOException {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        byte[] body = message.getBody();

        switch (routingKey) {
            // Two projections, each idempotent on its own (event, projection)
            // key and each in its own transaction. If the second throws, the
            // message is redelivered: the first is then skipped as already
            // handled and only the second is retried. Neither can roll the
            // other back.
            case "account.created" -> {
                AccountCreatedEvent event = objectMapper.readValue(body, AccountCreatedEvent.class);
                notificationService.handleAccountCreated(event);
                movementProjector.project(event);
            }
            case "transfer.completed" -> {
                TransferCompletedEvent event = objectMapper.readValue(body, TransferCompletedEvent.class);
                notificationService.handleTransferCompleted(event);
                movementProjector.project(event);
            }
            case "transfer.failed" ->
                    notificationService.handleTransferFailed(objectMapper.readValue(body, TransferFailedEvent.class));
            default -> {
                // Reachable only if a future publisher adds a new event under
                // "account.*"/"transfer.*" that this listener hasn't been
                // taught to handle yet. Retrying it would never succeed - no
                // number of attempts makes an unknown routing key known - so
                // log and drop rather than let the retry/DLQ machinery treat
                // it as a transient failure.
                log.warn("Ignoring message with unrecognized routing key: {}", routingKey);
            }
        }
    }
}
