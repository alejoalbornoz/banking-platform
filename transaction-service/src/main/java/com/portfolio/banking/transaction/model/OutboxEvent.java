package com.portfolio.banking.transaction.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbox pattern's whole point: writing this row happens in the SAME
 * local database transaction as the {@link Transaction} status update that
 * caused it. Since both writes go to the same Postgres database, that
 * transaction is atomic - either both commit or neither does. There is no
 * "the DB write succeeded but the event was lost" gap, because publishing
 * to RabbitMQ isn't part of this transaction at all; a separate relay
 * (see the messaging package) polls this table afterwards and publishes
 * whatever it finds.
 * <p>
 * Retrying is bounded rather than indefinite: {@code attempts} and
 * {@code deadAt} exist so that a row nothing can ever publish stops being
 * retried and starts being reported, instead of sitting at the head of every
 * poll forever with the whole outbox queued behind it.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    // Not @Lob: a JSON event payload is ordinary text, not a genuine "large
    // object". On Postgres, Hibernate 6 maps a @Lob String to the oid large
    // object type by default, which doesn't match the plain TEXT column the
    // migration actually creates and fails schema validation on startup.
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Failures that were about <em>this message</em>. A broker that is simply
     * down does not advance it - see {@code OutboxRelay} for why counting
     * those would turn an outage into data loss.
     */
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * Set once {@link #attempts} runs out: the relay has stopped trying, and
     * this event will not be delivered without someone intervening. Null for
     * every row that is still publishable.
     */
    @Column(name = "dead_at")
    private Instant deadAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
        // required by JPA
    }

    public OutboxEvent(String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payloadJson;
    }

    public void markPublished() {
        this.published = true;
        this.publishedAt = Instant.now();
    }

    /** True once the relay has given up on this row. */
    public boolean isDead() {
        return deadAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isPublished() {
        return published;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getDeadAt() {
        return deadAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
