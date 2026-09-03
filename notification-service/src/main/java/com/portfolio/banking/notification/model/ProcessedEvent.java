package com.portfolio.banking.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * The dedup ledger for at-least-once delivery: one row per event this
 * service has ever handled, keyed by the event's own {@code eventId} - not a
 * generated id. Inserting the same eventId twice hits the primary key
 * constraint, which is what turns a redelivered message into a safe no-op
 * instead of a repeated side effect.
 * <p>
 * Deliberately separate from {@link Notification}: one event can produce
 * zero, one, or several notifications (a completed transfer notifies both
 * parties), but it is processed exactly once regardless of how many
 * notifications that produces.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // required by JPA
    }

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
