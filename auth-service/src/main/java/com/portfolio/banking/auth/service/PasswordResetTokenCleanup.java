package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.repository.IPasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes reset tokens well past their (fifteen-minute) expiry.
 * <p>
 * Unlike the refresh-token cleanup, there is no forensic reason to keep these
 * around: a spent or expired reset token tells nobody anything, and every row
 * still holds the hash of something that was briefly equivalent to a
 * password. The shortest honest retention is the right one.
 */
@Component
public class PasswordResetTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenCleanup.class);

    /** Long enough that a clock skew between app and database cannot delete a live token. */
    private static final Duration GRACE = Duration.ofHours(1);

    private final IPasswordResetTokenRepository passwordResetTokenRepository;

    public PasswordResetTokenCleanup(IPasswordResetTokenRepository passwordResetTokenRepository) {
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }

    @Scheduled(
            fixedDelayString = "${banking.password-reset.cleanup-interval-ms}",
            initialDelayString = "${banking.password-reset.cleanup-interval-ms}")
    public void deleteExpiredTokens() {
        int deleted = passwordResetTokenRepository.deleteExpiredBefore(Instant.now().minus(GRACE));
        if (deleted > 0) {
            log.info("Deleted {} expired password reset tokens", deleted);
        }
    }
}
