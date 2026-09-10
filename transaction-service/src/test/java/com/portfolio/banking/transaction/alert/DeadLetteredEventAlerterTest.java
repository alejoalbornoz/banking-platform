package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real registry rather than a mock, like the other two alerter
 * tests: what matters is that the counter lands under the exact name the
 * alert rule points at.
 */
class DeadLetteredEventAlerterTest {

    private MeterRegistry meterRegistry;
    private DeadLetteredEventAlerter alerter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alerter = new DeadLetteredEventAlerter(meterRegistry);
    }

    @Test
    void registersTheCounterUpFront_soAlertRulesSeeZeroRatherThanNothing() {
        assertThat(counter()).isNotNull();
        assertThat(counter().count()).isZero();
    }

    @Test
    void alert_incrementsTheDeadLetterCounter() {
        alerter.alert(deadEvent(), 10, "payload is not convertible");

        assertThat(counter().count()).isEqualTo(1.0);
    }

    /**
     * Pins the name because {@code prometheus/alerts.yml} references it as
     * {@code banking_outbox_dead_lettered_total}; renaming it here would
     * silently leave that rule matching nothing.
     */
    @Test
    void theCounterNameIsTheOneTheAlertRuleReferences() {
        assertThat(DeadLetteredEventAlerter.DEAD_LETTERED_COUNTER)
                .isEqualTo("banking.outbox.dead_lettered");
    }

    @Test
    void theCounterCarriesNoPerEventTags() {
        alerter.alert(deadEvent(), 10, "one reason");
        alerter.alert(deadEvent(), 10, "another reason");

        assertThat(meterRegistry.find(DeadLetteredEventAlerter.DEAD_LETTERED_COUNTER).counters())
                .as("two dead letters, still one time series")
                .hasSize(1);
        assertThat(counter().getId().getTags()).isEmpty();
    }

    private Counter counter() {
        return meterRegistry.find(DeadLetteredEventAlerter.DEAD_LETTERED_COUNTER).counter();
    }

    private static OutboxEvent deadEvent() {
        OutboxEvent event = new OutboxEvent(
                "Transaction", UUID.randomUUID(), "transfer.completed", "{}");
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
