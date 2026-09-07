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
        ProcessedEvent event = new ProcessedEvent(UUID.randomUUID());

        // With an assigned id, Spring Data's default answer here is false,
        // which turns save() into a merge - and a merge on a redelivered
        // event quietly UPDATEs the existing row instead of violating the
        // primary key, defeating the deduplication entirely.
        assertThat(event.isNew()).isTrue();
        assertThat(event.getId()).isEqualTo(event.getEventId());
    }
}
