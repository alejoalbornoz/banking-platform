package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.exception.TooManyLoginAttemptsException;
import com.portfolio.banking.auth.model.LoginAttempt;
import com.portfolio.banking.auth.repository.ILoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Slows down repeated failed logins for one address.
 * <p>
 * <b>Backoff, not lockout.</b> A hard lockout after N failures hands an
 * attacker a denial-of-service primitive: knowing somebody's email address
 * would be enough to keep them out of their own account indefinitely, and
 * "lock the victim out" is a cheaper attack than the one being prevented. A
 * delay that doubles has the property that actually matters - guessing
 * becomes hopeless long before the numbers get large - while a real person
 * who mistypes twice waits seconds, and waiting is all it ever takes.
 * <p>
 * <b>The delay is derived, not stored.</b> There is no lock to set and no
 * lock to expire; {@code failed_count} and {@code last_failure_at} are enough
 * to say how long this address is being held off right now. So nothing has to
 * run on a schedule to let anybody back in, which is the failure mode of
 * lockout tables - the row that never got unlocked.
 * <p>
 * <b>It counts addresses, not accounts.</b> Attempts against addresses nobody
 * registered are throttled identically. Doing otherwise would make the
 * throttle answer the question the login response is careful not to: five
 * tries then 429, versus 401 forever, is a perfectly good user-enumeration
 * oracle.
 * <p>
 * What this deliberately is not: protection against a distributed attack
 * spread thinly across many addresses, or against one address attacked from
 * thousands of hosts. Both want a per-IP limit at the edge, which needs
 * shared state at the gateway and a decision about how much to trust
 * {@code X-Forwarded-For}. See "Known gaps" in the README.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final ILoginAttemptRepository loginAttemptRepository;
    private final int freeAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration resetAfter;

    /**
     * REQUIRES_NEW, for the same reason the refresh-token revocation needs it:
     * {@code AuthService.login} is {@code @Transactional} and a failed login
     * ends by throwing, so a failure recorded in that transaction would be
     * rolled back along with it - and a counter that resets every time it
     * counts something is not a counter. This is exactly the bug that only
     * shows up in production as "we throttle these and it never seems to do
     * anything."
     */
    private final TransactionTemplate ownTransaction;

    public LoginThrottle(ILoginAttemptRepository loginAttemptRepository,
                          PlatformTransactionManager transactionManager,
                          @Value("${banking.security.login-throttle.free-attempts}") int freeAttempts,
                          @Value("${banking.security.login-throttle.base-delay-seconds}") long baseDelaySeconds,
                          @Value("${banking.security.login-throttle.max-delay-seconds}") long maxDelaySeconds,
                          @Value("${banking.security.login-throttle.reset-after-seconds}") long resetAfterSeconds) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.freeAttempts = freeAttempts;
        this.baseDelay = Duration.ofSeconds(baseDelaySeconds);
        this.maxDelay = Duration.ofSeconds(maxDelaySeconds);
        this.resetAfter = Duration.ofSeconds(resetAfterSeconds);

        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Called before the password is looked at, so a held-off address costs no
     * bcrypt work - which is the point of throttling at all. An attacker who
     * could still make the server hash a password on every rejected attempt
     * would have a cheap way to spend the server's CPU.
     *
     * @throws TooManyLoginAttemptsException if this address is still inside
     *         its backoff window
     */
    public void assertNotThrottled(String email, Instant now) {
        Optional<Duration> remaining = loginAttemptRepository.findById(email)
                .map(attempt -> remainingDelay(attempt, now))
                .filter(delay -> !delay.isZero() && !delay.isNegative());

        if (remaining.isPresent()) {
            log.debug("Login attempt for {} held off for another {}", email, remaining.get());
            throw new TooManyLoginAttemptsException(remaining.get());
        }
    }

    public void recordFailure(String email, Instant now) {
        ownTransaction.executeWithoutResult(status ->
                loginAttemptRepository.recordFailure(email, now, now.minus(resetAfter)));
    }

    /**
     * Clears the counter, so an honest user who finally remembers their
     * password is not still serving out a delay earned before they did. Runs
     * in its own transaction to stay symmetric with {@link #recordFailure} -
     * and because a successful login has genuinely happened by this point,
     * whatever the rest of the request goes on to do.
     */
    public void recordSuccess(String email) {
        ownTransaction.executeWithoutResult(status -> loginAttemptRepository.clear(email));
    }

    /**
     * How much longer this address is held off, or zero if it may try now.
     * <p>
     * Two things make the delay expire on its own. The window is measured from
     * the <em>last</em> failure, so simply waiting is always enough; and a
     * count older than {@code resetAfter} is treated as spent, which matters
     * for the row that is about to be reset by the next failure but has not
     * been yet.
     */
    private Duration remainingDelay(LoginAttempt attempt, Instant now) {
        if (attempt.getLastFailureAt().isBefore(now.minus(resetAfter))) {
            return Duration.ZERO;
        }
        Instant releaseAt = attempt.getLastFailureAt().plus(delayFor(attempt.getFailedCount()));
        return releaseAt.isAfter(now) ? Duration.between(now, releaseAt) : Duration.ZERO;
    }

    /**
     * Doubles per failure past the free ones, capped. With the defaults
     * (5 free, 2s base, 15min cap) the 6th failure costs 2 seconds and the
     * 14th costs about eight minutes - so a person who fat-fingers their
     * password a few times never notices, and anybody working through a
     * dictionary is down to a handful of guesses an hour before they have
     * covered anything.
     */
    private Duration delayFor(int failedCount) {
        int over = failedCount - freeAttempts;
        if (over <= 0) {
            return Duration.ZERO;
        }
        // Shift rather than Math.pow, and clamp the exponent: a long-running
        // attack pushes failedCount arbitrarily high, and 1L << 64 wraps
        // around to 1 rather than overflowing to something large.
        int exponent = Math.min(over - 1, 32);
        Duration delay = baseDelay.multipliedBy(1L << exponent);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
