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
 * <p>
 * Only one constructor: both take an explicit id and timestamp. A subclass
 * exposes two of its own on top of this - one that mints a fresh id/timestamp
 * for a publisher creating a brand new event, and one annotated for Jackson
 * that reconstructs an event exactly as it came off the wire. See
 * {@link AccountCreatedEvent} for why the second one has to exist at all.
 */
public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant occurredAt;

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
