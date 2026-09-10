package com.portfolio.banking.transaction.messaging;

import com.portfolio.banking.transaction.alert.IDeadLetteredEventAlerter;
import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's failure handling, which is the whole of its behaviour that is
 * not one line of happy path.
 * <p>
 * The case worth the most here is the broker outage. Getting it wrong does
 * not look like a bug in testing - events still publish, nothing throws - it
 * looks like a bug the first time RabbitMQ is down for a quarter of an hour
 * and the entire backlog is quietly dead-lettered, which is the one outcome
 * the outbox pattern exists to make impossible.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final String EXCHANGE = "banking.events";

    @Mock
    private IOutboxEventRepository outboxEventRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private IDeadLetteredEventAlerter deadLetteredEventAlerter;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate over a mocked manager: commit and rollback
        // are no-ops, but the callback runs for real, so an exception thrown
        // inside it propagates exactly as it would in production.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        relay = new OutboxRelay(outboxEventRepository, rabbitTemplate,
                new TransactionTemplate(transactionManager), deadLetteredEventAlerter,
                EXCHANGE, MAX_ATTEMPTS);
    }

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        OutboxEvent event = pendingEvent(0);
        givenPending(event);

        relay.relayPendingEvents();

        verify(rabbitTemplate).send(eq(EXCHANGE), eq("transfer.completed"), any(Message.class));
        verify(outboxEventRepository).save(event);
        assertThat(event.isPublished()).isTrue();
    }

    /**
     * The one that matters. A broker outage is not any single row's fault, so
     * it must not spend any row's attempt budget - the relay polls every two
     * seconds, so a budget of ten would be gone in twenty and the whole
     * backlog would be dead-lettered while RabbitMQ was merely restarting.
     */
    @Test
    void whenTheBrokerIsUnreachable_nothingIsCountedAgainstAnyEvent() {
        givenPending(pendingEvent(0), pendingEvent(0), pendingEvent(0));
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxEventRepository, never()).recordFailure(any(), anyString());
        verify(outboxEventRepository, never()).recordFailureAndMarkDead(any(), anyString(), any());
        verify(deadLetteredEventAlerter, never()).alert(any(), org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    /**
     * And it stops the cycle rather than working through the batch: every
     * remaining event is in exactly the same position, so the other attempts
     * would only produce the same exception again.
     */
    @Test
    void whenTheBrokerIsUnreachable_theRestOfTheBatchIsNotAttempted() {
        givenPending(pendingEvent(0), pendingEvent(0), pendingEvent(0));
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        relay.relayPendingEvents();

        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void aFailureAboutTheMessageItself_countsAgainstThatEvent() {
        OutboxEvent event = pendingEvent(0);
        givenPending(event);
        doThrow(new MessageConversionException("payload is not convertible"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxEventRepository).recordFailure(eq(event.getId()), anyString());
        verify(outboxEventRepository, never()).recordFailureAndMarkDead(any(), anyString(), any());
    }

    /**
     * A single hopeless event must not take the batch with it. Before the
     * per-event catch, one throwing row aborted the whole cycle - and since
     * the poll is ordered oldest-first, that row came back first every time
     * and nothing behind it ever went out.
     */
    @Test
    void aFailingEventDoesNotBlockTheOnesBehindIt() {
        OutboxEvent poisoned = pendingEvent(0);
        OutboxEvent healthy = pendingEvent(0);
        givenPending(poisoned, healthy);
        doThrow(new MessageConversionException("payload is not convertible"))
                .doNothing()
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        relay.relayPendingEvents();

        verify(rabbitTemplate, times(2)).send(anyString(), anyString(), any(Message.class));
        assertThat(healthy.isPublished()).isTrue();
    }

    @Test
    void onTheLastAttempt_theEventIsParkedAndReported() {
        OutboxEvent event = pendingEvent(MAX_ATTEMPTS - 1);
        givenPending(event);
        doThrow(new MessageConversionException("payload is not convertible"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        relay.relayPendingEvents();

        verify(outboxEventRepository).recordFailureAndMarkDead(eq(event.getId()), anyString(), any(Instant.class));
        verify(outboxEventRepository, never()).recordFailure(any(), anyString());
        verify(deadLetteredEventAlerter).alert(eq(event), eq(MAX_ATTEMPTS), anyString());
    }

    /**
     * The row is already dead when the alert is raised, so an unreachable
     * pager must not propagate: it would abort the batch and reintroduce
     * exactly the stall this class was changed to remove.
     */
    @Test
    void whenAlertingItselfFails_theBatchCarriesOn() {
        OutboxEvent doomed = pendingEvent(MAX_ATTEMPTS - 1);
        OutboxEvent healthy = pendingEvent(0);
        givenPending(doomed, healthy);
        doThrow(new MessageConversionException("payload is not convertible"))
                .doNothing()
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
        doThrow(new IllegalStateException("pager unreachable"))
                .when(deadLetteredEventAlerter).alert(any(), org.mockito.ArgumentMatchers.anyInt(), anyString());

        relay.relayPendingEvents();

        verify(outboxEventRepository).recordFailureAndMarkDead(eq(doomed.getId()), anyString(), any(Instant.class));
        assertThat(healthy.isPublished()).isTrue();
    }

    private void givenPending(OutboxEvent... events) {
        when(outboxEventRepository.findByPublishedFalseAndDeadAtIsNullOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(events));
    }

    /** {@code id} and {@code attempts} are database-owned; tests set them directly. */
    private static OutboxEvent pendingEvent(int attempts) {
        OutboxEvent event = new OutboxEvent(
                "Transaction", UUID.randomUUID(), "transfer.completed", "{\"eventId\":\"x\"}");
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(event, "attempts", attempts);
        return event;
    }
}
