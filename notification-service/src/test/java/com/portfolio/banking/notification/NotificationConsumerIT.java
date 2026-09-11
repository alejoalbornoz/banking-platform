package com.portfolio.banking.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.notification.model.Notification;
import com.portfolio.banking.notification.model.Movement;
import com.portfolio.banking.notification.model.MovementKind;
import com.portfolio.banking.notification.repository.IMovementRepository;
import com.portfolio.banking.notification.repository.INotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
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

    @Autowired
    private IMovementRepository movementRepository;

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
                    notificationRepository.findFirstPageByRecipientAccountId(accountId, Pageable.ofSize(200));
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
                assertThat(notificationRepository.findFirstPageByRecipientAccountId(accountId, Pageable.ofSize(200)))
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
            assertThat(notificationRepository.findFirstPageByRecipientAccountId(sourceId, Pageable.ofSize(200)))
                    .hasSize(1);
            assertThat(notificationRepository.findFirstPageByRecipientAccountId(destinationId, Pageable.ofSize(200)))
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

    /**
     * The two projections over one event, and the property that makes them
     * two: each is idempotent on its own marker. Only a database shows that
     * the same event yields one row for each of two projection names rather
     * than one marker shared between them.
     */
    @Test
    void oneEvent_feedsBothProjections_eachRecordingItsOwnMarker() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        UUID sourceOwner = UUID.randomUUID();
        UUID destinationOwner = UUID.randomUUID();
        publish("account.created", new AccountCreatedEvent(sourceId, "111111111111", sourceOwner, BigDecimal.ZERO, "USD"));
        publish("account.created", new AccountCreatedEvent(destinationId, "222222222222", destinationOwner, BigDecimal.ZERO, "USD"));
        TransferCompletedEvent transfer = new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("40.00"), "USD");

        publish("transfer.completed", transfer);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Movement> mine = movementRepository.findFirstPageByOwner(sourceOwner, null, Pageable.ofSize(50));
            assertThat(mine).singleElement().satisfies(m -> {
                assertThat(m.getKind()).isEqualTo(MovementKind.SENT);
                assertThat(m.getCounterpartyAccountId()).isEqualTo(destinationId);
                assertThat(m.getTransactionId()).isEqualTo(transfer.getTransactionId());
            });
            assertThat(movementRepository.findFirstPageByOwner(destinationOwner, null, Pageable.ofSize(50)))
                    .singleElement().satisfies(m -> assertThat(m.getKind()).isEqualTo(MovementKind.RECEIVED));
            // And the notification projection ran too, on the same event.
            assertThat(notificationRepository.findFirstPageByRecipientAccountId(sourceId, Pageable.ofSize(50))).hasSize(1);
        });
    }

    /**
     * Events arriving in the wrong order, for real: the transfer is published
     * BEFORE the creation of its destination account. The movement must be
     * written without an owner and then claimed once the creation arrives -
     * and the claim is a SQL UPDATE that only a database can be shown to
     * perform.
     */
    @Test
    void aTransferArrivingBeforeItsAccountWasCreated_isClaimedOnceTheCreationArrives() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID lateAccountId = UUID.randomUUID();
        UUID sourceOwner = UUID.randomUUID();
        UUID lateOwner = UUID.randomUUID();
        publish("account.created", new AccountCreatedEvent(sourceId, "333333333333", sourceOwner, BigDecimal.ZERO, "USD"));
        publish("transfer.completed", new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, lateAccountId, new BigDecimal("10.00"), "USD"));

        // The RECEIVED row exists but belongs to nobody yet.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(movementRepository.findAll())
                        .filteredOn(m -> m.getAccountId().equals(lateAccountId))
                        .singleElement()
                        .satisfies(m -> assertThat(m.getOwnerId()).isNull()));
        assertThat(movementRepository.findFirstPageByOwner(lateOwner, null, Pageable.ofSize(50)))
                .as("not in anyone's statement yet")
                .isEmpty();

        // Now the creation catches up.
        publish("account.created", new AccountCreatedEvent(lateAccountId, "444444444444", lateOwner, BigDecimal.ZERO, "USD"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(movementRepository.findFirstPageByOwner(lateOwner, null, Pageable.ofSize(50)))
                        .as("claimed: same final state as if the events had arrived in order")
                        .singleElement()
                        .satisfies(m -> {
                            assertThat(m.getKind()).isEqualTo(MovementKind.RECEIVED);
                            assertThat(m.getCounterpartyAccountId()).isEqualTo(sourceId);
                        }));
    }

    private void publish(String routingKey, Object event) throws Exception {
        rabbitTemplate.send(exchangeName, routingKey, messageOf(objectMapper.writeValueAsBytes(event)));
    }

    private org.springframework.amqp.core.Message messageOf(byte[] body) {
        return org.springframework.amqp.core.MessageBuilder.withBody(body).build();
    }
}
