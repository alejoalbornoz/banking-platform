package com.portfolio.banking.auth.alert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Both channels an operations setup actually watches: a metric to alert
 * <em>on</em>, and a log line to read once it fires.
 * <p>
 * <b>This counter's baseline is not zero, and that changes the alert rule.</b>
 * {@code banking.transfers.stuck} is correct only at zero, so any increase at
 * all is an incident. Reuse is different: a client that fires two refreshes
 * at once trips detection with nothing stolen, so a rule of "any increase"
 * would page somebody over a double-clicked button. What actually
 * distinguishes an attack is <em>rate</em> - several in a short window, or a
 * sustained trickle - which is why the shipped rule thresholds instead of
 * triggering on the first event. See {@code prometheus/alerts.yml}.
 * <p>
 * Untagged, for the same reason as the stuck-transfer counter: tagging by
 * family or user id would give a metrics backend one new time series per
 * incident, which is how cardinality explosions happen. The identifiers
 * belong in the log line.
 */
@Component
public class RefreshTokenReuseAlerter implements IRefreshTokenReuseAlerter {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseAlerter.class);

    /** Matches transaction-service's marker, so one log pipeline rule covers both services. */
    private static final Marker OPS_ALERT = MarkerFactory.getMarker("OPS_ALERT");

    static final String REUSE_COUNTER = "banking.auth.refresh_token.reuse_detected";

    private final Counter reuseDetected;

    public RefreshTokenReuseAlerter(MeterRegistry meterRegistry) {
        // Registered at construction rather than on first use, so the series
        // exists and reads zero from startup. A counter that only appears
        // once something goes wrong gives an alert rule nothing to evaluate
        // until the incident it is meant to catch has already happened.
        this.reuseDetected = Counter.builder(REUSE_COUNTER)
                .description("Refresh tokens presented after they were already spent. Each one revoked a "
                        + "token family: either a stolen token was replayed, or a client refreshed twice.")
                .register(meterRegistry);
    }

    @Override
    public void alert(UUID familyId, UUID userId) {
        reuseDetected.increment();
        log.warn(OPS_ALERT,
                "REFRESH TOKEN REUSE - revoking token family {} for user {}. Either the token was stolen, "
                        + "or a client refreshed twice with the same token; both look identical from here.",
                familyId, userId);
    }
}
