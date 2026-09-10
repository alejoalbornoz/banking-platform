package com.portfolio.banking.auth.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real {@link SimpleMeterRegistry} rather than a mocked one, for
 * the same reason as {@code StuckTransferAlerterTest} in
 * transaction-service: what is worth asserting is that the counter actually
 * lands in a registry under the exact name an alert rule points at. A mock
 * would only prove some method was called.
 */
class RefreshTokenReuseAlerterTest {

    private MeterRegistry meterRegistry;
    private RefreshTokenReuseAlerter alerter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alerter = new RefreshTokenReuseAlerter(meterRegistry);
    }

    @Test
    void registersTheCounterUpFront_soAlertRulesSeeZeroRatherThanNothing() {
        // A series that only appears after the first incident gives a rule
        // nothing to evaluate until the thing it exists to catch has already
        // happened.
        assertThat(counter()).isNotNull();
        assertThat(counter().count()).isZero();
    }

    @Test
    void alert_incrementsTheReuseCounter() {
        alerter.alert(UUID.randomUUID(), UUID.randomUUID());

        assertThat(counter().count()).isEqualTo(1.0);
    }

    /**
     * Pins the metric name, because it is not really an implementation
     * detail: {@code prometheus/alerts.yml} names it (as
     * {@code banking_auth_refresh_token_reuse_detected_total}, after
     * Micrometer's dot-to-underscore and {@code _total} conventions), and
     * renaming it here would silently make that rule match nothing at all.
     */
    @Test
    void theCounterNameIsTheOneTheAlertRuleReferences() {
        assertThat(RefreshTokenReuseAlerter.REUSE_COUNTER)
                .isEqualTo("banking.auth.refresh_token.reuse_detected");
    }

    /**
     * Untagged on purpose: a tag per family or per user would give a metrics
     * backend one new time series per incident, which is how cardinality
     * explosions start. The ids live in the log line instead.
     */
    @Test
    void theCounterCarriesNoPerIncidentTags() {
        alerter.alert(UUID.randomUUID(), UUID.randomUUID());
        alerter.alert(UUID.randomUUID(), UUID.randomUUID());

        assertThat(meterRegistry.find(RefreshTokenReuseAlerter.REUSE_COUNTER).counters())
                .as("two incidents, still one time series")
                .hasSize(1);
        assertThat(counter().getId().getTags()).isEmpty();
        assertThat(counter().count()).isEqualTo(2.0);
    }

    private Counter counter() {
        return meterRegistry.find(RefreshTokenReuseAlerter.REUSE_COUNTER).counter();
    }
}
