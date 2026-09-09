package com.portfolio.banking.auth.repository;

import com.portfolio.banking.auth.model.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ILoginAttemptRepository extends JpaRepository<LoginAttempt, String> {

    /**
     * Records one failed attempt.
     * <p>
     * Native, because this is an upsert and JPQL has no way to express one.
     * That is not a workaround: read-then-write is wrong here for the same
     * reason it is wrong in the ledger and in refresh-token consumption -
     * several failed logins for one address can arrive at once, and if each
     * reads the count before any of them writes it, a burst of twenty
     * attempts advances the counter by one. {@code ON CONFLICT DO UPDATE}
     * makes the increment atomic, so the count is the number of attempts
     * rather than the number of attempts that happened to be spaced out.
     * <p>
     * The CASE is the decay: a first failure after a long quiet period starts
     * over at 1 instead of resuming wherever a user left off weeks ago. It
     * lives in the same statement so that resetting is atomic too.
     * <p>
     * Returns rows affected, not the new count - nothing needs the count here.
     * Whether a caller is currently held off is decided when the next attempt
     * arrives, by reading the row, so recording a failure has no answer to
     * give.
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            INSERT INTO login_attempts (email, failed_count, last_failure_at)
            VALUES (:email, 1, :now)
            ON CONFLICT (email) DO UPDATE
               SET failed_count = CASE
                       WHEN login_attempts.last_failure_at < :resetBefore THEN 1
                       ELSE login_attempts.failed_count + 1
                   END,
                   last_failure_at = :now
            """)
    int recordFailure(@Param("email") String email,
                       @Param("now") Instant now,
                       @Param("resetBefore") Instant resetBefore);

    /**
     * Clears the counter after a successful login. Deleting rather than
     * zeroing keeps the table to only the addresses currently failing.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM LoginAttempt a WHERE a.email = :email")
    int clear(@Param("email") String email);

    /** Drops counters that have already decayed to nothing. */
    @Modifying
    @Transactional
    @Query("DELETE FROM LoginAttempt a WHERE a.lastFailureAt < :cutoff")
    int deleteQuietSince(@Param("cutoff") Instant cutoff);
}
