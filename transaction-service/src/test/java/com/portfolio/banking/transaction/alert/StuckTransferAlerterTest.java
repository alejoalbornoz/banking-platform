package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tested against a real {@link SimpleMeterRegistry} rather than a mocked
 * one: the thing worth asserting is that the counter actually lands in a
 * registry under the expected name, which is precisely what an alert rule
 * will be pointed at. A mock would only prove that some method was called.
 */
class StuckTransferAlerterTest {

    private MeterRegistry meterRegistry;
    private StuckTransferAlerter alerter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alerter = new StuckTransferAlerter(meterRegistry);
    }

    @Test
    void registersTheCounterUpFront_soAlertRulesSeeZeroRatherThanNothing() {
        // A counter that only appears after the first incident is a counter
        // no alert rule can be written against ahead of time.
        assertThat(counter()).isNotNull();
        assertThat(counter().count()).isZero();
    }

    @Test
    void alert_incrementsTheStuckTransferCounter() {
        alerter.alert(stuckTransfer(), "destination account not found", "source account vanished too");

        assertThat(counter().count()).isEqualTo(1.0);
    }

    @Test
    void alert_countsEveryOccurrence() {
        alerter.alert(stuckTransfer(), "credit failed", "compensation failed");
        alerter.alert(stuckTransfer(), "credit failed", "compensation failed");

        assertThat(counter().count()).isEqualTo(2.0);
    }

    private Counter counter() {
        return meterRegistry.find(StuckTransferAlerter.STUCK_TRANSFERS_COUNTER).counter();
    }

    private static Transaction stuckTransfer() {
        return new Transaction("key-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("50.00"), "USD", UUID.randomUUID());
    }
}
