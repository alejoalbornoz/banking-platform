package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.OutboxEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

/**
 * Same two channels as {@link StuckTransferAlerter}, and the same correct
 * value: zero. Every event in the outbox describes a state change that has
 * already committed, so one that never gets published is a permanent
 * disagreement between this service and everyone downstream - notification
 * -service will never know that transfer completed, and nothing will ever
 * reconcile it. That makes the alert rule the simple kind: any increase at
 * all.
 * <p>
 * Untagged, like the other two counters: event type would be a bounded and
 * therefore tempting tag, but the aggregate id in the log line is what an
 * investigation actually needs, and adding one tag now is how a metric ends
 * up with five later.
 */
@Component
public class DeadLetteredEventAlerter implements IDeadLetteredEventAlerter {

    private static final Logger log = LoggerFactory.getLogger(DeadLetteredEventAlerter.class);

    /** Shared with the other alerters so one log-pipeline rule covers them all. */
    private static final Marker OPS_ALERT = MarkerFactory.getMarker("OPS_ALERT");

    static final String DEAD_LETTERED_COUNTER = "banking.outbox.dead_lettered";

    private final Counter deadLettered;

    public DeadLetteredEventAlerter(MeterRegistry meterRegistry) {
        this.deadLettered = Counter.builder(DEAD_LETTERED_COUNTER)
                .description("Outbox events the relay gave up on. Each one is a committed state change "
                        + "that will never reach its consumers. Should always be zero.")
                .register(meterRegistry);
    }

    @Override
    public void alert(OutboxEvent event, int attempts, String reason) {
        deadLettered.increment();
        log.error(OPS_ALERT,
                "DEAD-LETTERED OUTBOX EVENT - this state change will never reach its consumers. "
                        + "eventId={} eventType={} aggregateType={} aggregateId={} attempts={} lastError=[{}]",
                event.getId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), attempts, reason);
    }
}
