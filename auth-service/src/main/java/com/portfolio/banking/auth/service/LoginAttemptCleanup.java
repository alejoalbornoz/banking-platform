package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.repository.ILoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes login-attempt counters that have already decayed to nothing.
 * <p>
 * This table is unusual in that anyone can add a row to it without an
 * account, without a password, and without ever succeeding at anything -
 * naming an address in a login request is enough. That is deliberate (see the
 * migration), and it is exactly why the rows have to be forgotten: they are a
 * decaying counter, not a record, and without this a spray of attempts across
 * made-up addresses would leave one row each behind forever.
 * <p>
 * Deleting a row is safe once it is past the reset window, because a counter
 * that old would be treated as starting from scratch anyway - the delete only
 * makes explicit what {@code LoginThrottle} already concluded.
 */
@Component
public class LoginAttemptCleanup {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptCleanup.class);

    private final ILoginAttemptRepository loginAttemptRepository;
    private final Duration resetAfter;

    public LoginAttemptCleanup(ILoginAttemptRepository loginAttemptRepository,
                                @Value("${banking.security.login-throttle.reset-after-seconds}") long resetAfterSeconds) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.resetAfter = Duration.ofSeconds(resetAfterSeconds);
    }

    @Scheduled(
            fixedDelayString = "${banking.security.login-throttle.cleanup-interval-ms}",
            initialDelayString = "${banking.security.login-throttle.cleanup-interval-ms}")
    public void deleteDecayedCounters() {
        int deleted = loginAttemptRepository.deleteQuietSince(Instant.now().minus(resetAfter));
        if (deleted > 0) {
            log.info("Deleted {} login-attempt counters quiet for more than {}", deleted, resetAfter);
        }
    }
}
