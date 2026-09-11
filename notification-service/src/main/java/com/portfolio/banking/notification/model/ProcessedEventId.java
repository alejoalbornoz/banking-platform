package com.portfolio.banking.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * (event, projection): the unit of "already handled".
 * <p>
 * Not just the event id, because two projections consume the same stream
 * and each has to be able to say, independently, whether it has seen a given
 * event. A replay must be skipped by the projection that already handled it
 * and taken by the one that did not - and that is only expressible if the
 * projection is part of the key.
 */
@Embeddable
public class ProcessedEventId implements Serializable {

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false, length = 32)
    private String projection;

    protected ProcessedEventId() {
        // required by JPA
    }

    public ProcessedEventId(UUID eventId, String projection) {
        this.eventId = eventId;
        this.projection = projection;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getProjection() {
        return projection;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProcessedEventId other
                && eventId.equals(other.eventId)
                && projection.equals(other.projection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, projection);
    }
}
