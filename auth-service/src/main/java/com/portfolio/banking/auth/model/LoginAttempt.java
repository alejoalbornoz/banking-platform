package com.portfolio.banking.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A decaying counter of consecutive failed logins for one submitted address.
 * <p>
 * Read-only from Java's side: the row is only ever created or advanced by the
 * upsert in {@code ILoginAttemptRepository.recordFailure}, because two failed
 * logins arriving together must not both read the same count and both write
 * it back as count+1. Nothing here has a setter for that reason.
 */
@Entity
@Table(name = "login_attempts")
public class LoginAttempt {

    /**
     * The address as submitted, lowercased - not a user id, and not a foreign
     * key. Attempts against addresses nobody has registered are counted
     * exactly like attempts against real ones; see the migration for why that
     * is the entire point.
     */
    @Id
    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "last_failure_at", nullable = false)
    private Instant lastFailureAt;

    protected LoginAttempt() {
        // required by JPA
    }

    public String getEmail() {
        return email;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }
}
