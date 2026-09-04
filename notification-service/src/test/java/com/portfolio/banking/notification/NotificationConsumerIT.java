package com.portfolio.banking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.notification.model.Notification;
import com.portfolio.banking.notification.repository.INotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Publishes real messages onto a real {@code banking.events} exchange and
 * verifies what {@code BankingEventListener} actually does with them - the
 * one part of this service no unit test touches at all, since the listener,
 * the queue/DLQ topology, and the retry advice chain are pure Spring AMQP
 * wiring that only exists once a container is actually running.
 * <p>
 * Consumption is asynchronous relative to the publish call, so every
 * assertion here polls with Awaitility rather than asserting immediately.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationConsumerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private INotificationRepository notificationRepository;

    @Value("${banking.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${banking.rabbitmq.dead-letter-queue}")
    private String deadLetterQueueName;

    @Test
    void accountCreatedEvent_isConsumedAndRecordedAsANotification() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountCreatedEvent event = new AccountCreatedEvent(
                accountId, "123456789012", UUID.randomUUID(), new BigDecimal("100.00"), "USD");

        publish("account.created", event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Notification> notifications =
                    notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(accountId);
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getMessage()).contains("123456789012", "100.00", "USD");
        });
    }

    /**
     * The exact hazard at-least-once delivery guarantees will eventually
     * produce: the same message, with the same eventId, delivered twice.
     * Nothing here tells the listener not to process it again - the unique
     * constraint on {@code processed_events.event_id} is what has to catch
     * it, against a real database, under a real second delivery.
     */
    @Test
    void redeliveredEvent_isRecordedOnlyOnce() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountCreatedEvent event = new AccountCreatedEvent(
                accountId, "999999999999", UUID.randomUUID(), new BigDecimal("50.00"), "USD");
        byte[] payload = objectMapper.writeValueAsBytes(event);

        rabbitTemplate.send(exchangeName, "account.created", messageOf(payload));
        rabbitTemplate.send(exchangeName, "account.created", messageOf(payload));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(accountId))
                        .as("one notification despite two deliveries of the same event")
                        .hasSize(1));
    }

    @Test
    void transferCompletedEvent_notifiesBothSenderAndReceiver() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        TransferCompletedEvent event = new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("75.00"), "USD");

        publish("transfer.completed", event);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(sourceId))
                    .hasSize(1);
            assertThat(notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(destinationId))
                    .hasSize(1);
        });
    }

    /**
     * Proves the retry-then-dead-letter wiring, not just that it compiles.
     * A payload that can never parse exhausts the container's local retries
     * the same way every time, so {@code RejectAndDontRequeueRecoverer}
     * rejects it without requeueing - which, because
     * {@code notification-service.events.queue} carries the
     * {@code x-dead-letter-exchange} argument, is what actually routes it to
     * the DLQ instead of looping on the main queue forever.
     */
    @Test
    void unparseablePayload_endsUpOnTheDeadLetterQueue() {
        rabbitTemplate.send(exchangeName, "account.created",
                messageOf("this is not valid JSON".getBytes(StandardCharsets.UTF_8)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer messageCount = rabbitAdmin.getQueueProperties(deadLetterQueueName) == null ? null
                    : (Integer) rabbitAdmin.getQueueProperties(deadLetterQueueName)
                            .get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            assertThat(messageCount).isNotNull().isGreaterThanOrEqualTo(1);
        });
    }

    private void publish(String routingKey, Object event) throws Exception {
        rabbitTemplate.send(exchangeName, routingKey, messageOf(objectMapper.writeValueAsBytes(event)));
    }

    private org.springframework.amqp.core.Message messageOf(byte[] body) {
        return org.springframework.amqp.core.MessageBuilder.withBody(body).build();
    }
}
