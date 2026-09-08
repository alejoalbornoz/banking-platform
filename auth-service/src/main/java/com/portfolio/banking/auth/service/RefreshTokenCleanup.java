package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.repository.IRefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes refresh tokens that expired long enough ago to be of no further
 * use.
 * <p>
 * Rotation means this table gains a row on every single refresh, and nothing
 * ever updates one twice - so without this it grows for the entire life of
 * the deployment, in proportion to traffic rather than to users. That is the
 * kind of table that is fine for a year and then is not.
 * <p>
 * The retention window after expiry is deliberate rather than a rounding
 * error. A token deleted the instant it expires comes back from
 * {@code findByTokenHash} as <em>unknown</em>, which is indistinguishable
 * from a fabricated one; kept a while longer, it reads as what it actually
 * is. Same 401 either way for the caller, but a very different story in the
 * logs when someone is investigating.
 */
@Component
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private final IRefreshTokenRepository refreshTokenRepository;
    private final Duration retainExpiredFor;

    public RefreshTokenCleanup(IRefreshTokenRepository refreshTokenRepository,
                                @Value("${banking.jwt.refresh-token-retention-after-expiry-seconds}") long retainSeconds) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.retainExpiredFor = Duration.ofSeconds(retainSeconds);
    }

    /**
     * {@code fixedDelay}, not {@code fixedRate}: the next run is scheduled
     * from the end of the previous one, so a slow delete on a large backlog
     * can't have a second copy of itself start on top of it.
     */
    @Scheduled(
            fixedDelayString = "${banking.jwt.refresh-token-cleanup-interval-ms}",
            initialDelayString = "${banking.jwt.refresh-token-cleanup-interval-ms}")
    public void deleteLongExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(Instant.now().minus(retainExpiredFor));
        if (deleted > 0) {
            log.info("Deleted {} refresh tokens that expired more than {} ago", deleted, retainExpiredFor);
        }
    }
}
