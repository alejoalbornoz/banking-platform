package com.portfolio.banking.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * The dedup ledger for at-least-once delivery: one row per event this
 * service has ever handled, keyed by the event's own {@code eventId} - not a
 * generated id. Inserting the same eventId twice hits the primary key
 * constraint, which is what turns a redelivered message into a safe no-op
 * instead of a repeated side effect - but only because {@link #isNew()}
 * forces an INSERT; see its javadoc, this silently didn't work at first.
 * <p>
 * Deliberately separate from {@link Notification}: one event can produce
 * zero, one, or several notifications (a completed transfer notifies both
 * parties), but it is processed exactly once regardless of how many
 * notifications that produces.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

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

    @Override
    public UUID getId() {
        return eventId;
    }

    /**
     * Always true, and the dedup above does not work without it.
     * <p>
     * {@code eventId} is an <em>assigned</em> id, not a generated one - it's
     * the event's own id, which is the entire point. But Spring Data decides
     * between {@code persist} and {@code merge} by asking whether the entity
     * is new, and its default answer for an assigned id is "the id isn't
     * null, so this isn't new" - so {@code save} issued a {@code merge}. On a
     * redelivery that merge found the existing row and quietly UPDATEd it:
     * no primary key violation, no exception, and the caller carried on and
     * created the notification a second time. The constraint was never doing
     * the work the design assumed it was.
     * <p>
     * Returning true unconditionally is safe precisely because of how narrow
     * this entity is: rows here are only ever inserted, never loaded or
     * updated (there's no finder on the repository, by design). If that ever
     * changes, this has to change with it.
     */
    @Override
    public boolean isNew() {
        return true;
    }
}
