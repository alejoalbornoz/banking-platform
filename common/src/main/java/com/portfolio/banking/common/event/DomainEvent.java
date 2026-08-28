package com.portfolio.banking.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base contract for every event published onto RabbitMQ.
 * <p>
 * {@code eventId} lets consumers deduplicate if a message is redelivered
 * (at-least-once delivery is the default assumption for our queues).
 * {@code occurredAt} is set by the publisher, not the broker, so it reflects
 * business time even if the message sits in a queue for a while.
 */
public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant occurredAt;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID();
        this.occurredAt = Instant.now();
    }

    protected DomainEvent(UUID eventId, Instant occurredAt) {
        this.eventId = eventId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
