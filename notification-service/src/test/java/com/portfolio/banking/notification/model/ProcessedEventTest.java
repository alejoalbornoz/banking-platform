package com.portfolio.banking.notification.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one property the whole dedup mechanism rests on. This can't prove
 * the end-to-end behavior - only a real database can, and
 * {@code NotificationConsumerIT} is what actually caught this being broken -
 * but it does stop someone from deleting {@code isNew()} as apparent
 * boilerplate without noticing what it's load-bearing for.
 */
class ProcessedEventTest {

    @Test
    void isAlwaysNew_soSaveIssuesAnInsertRatherThanAMerge() {
        UUID eventId = UUID.randomUUID();
        ProcessedEvent event = new ProcessedEvent(eventId, "notifications");

        // With an assigned id, Spring Data's default answer here is false,
        // which turns save() into a merge - and a merge on a redelivered
        // event quietly UPDATEs the existing row instead of violating the
        // primary key, defeating the deduplication entirely.
        assertThat(event.isNew()).isTrue();
        assertThat(event.getId()).isEqualTo(new ProcessedEventId(eventId, "notifications"));
        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getProjection()).isEqualTo("notifications");
    }

    /**
     * The key is (event, projection), and the two halves are both part of
     * identity: the same event handled by two projections is two rows, not a
     * conflict. A value-equal id is what lets JPA find the row again.
     */
    @Test
    void theKeyIsTheEventAndTheProjection_together() {
        UUID eventId = UUID.randomUUID();

        assertThat(new ProcessedEventId(eventId, "notifications"))
                .isEqualTo(new ProcessedEventId(eventId, "notifications"))
                .isNotEqualTo(new ProcessedEventId(eventId, "movements"))
                .isNotEqualTo(new ProcessedEventId(UUID.randomUUID(), "notifications"));
        assertThat(new ProcessedEventId(eventId, "notifications").hashCode())
                .isEqualTo(new ProcessedEventId(eventId, "notifications").hashCode());
    }
}
