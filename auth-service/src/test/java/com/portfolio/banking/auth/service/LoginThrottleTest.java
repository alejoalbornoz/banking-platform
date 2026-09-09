package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.exception.TooManyLoginAttemptsException;
import com.portfolio.banking.auth.model.LoginAttempt;
import com.portfolio.banking.auth.repository.ILoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backoff policy, which is where the arithmetic lives. A delay that grows
 * too slowly protects nothing and one that grows too fast locks people out of
 * their own accounts, and neither shows up as a failure anywhere else.
 */
@ExtendWith(MockitoExtension.class)
class LoginThrottleTest {

    private static final int FREE_ATTEMPTS = 5;
    private static final long BASE_DELAY_SECONDS = 2;
    private static final long MAX_DELAY_SECONDS = 900;
    private static final long RESET_AFTER_SECONDS = 3600;

    private static final String EMAIL = "user@example.com";
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    @Mock
    private ILoginAttemptRepository loginAttemptRepository;

    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        // Real TransactionTemplate over a mocked manager, so the REQUIRES_NEW
        // callbacks actually run instead of being stubbed away.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        throttle = new LoginThrottle(loginAttemptRepository, transactionManager,
                FREE_ATTEMPTS, BASE_DELAY_SECONDS, MAX_DELAY_SECONDS, RESET_AFTER_SECONDS);
    }

    @Test
    void anAddressWithNoFailures_isNotHeldOff() {
        when(loginAttemptRepository.findById(EMAIL)).thenReturn(Optional.empty());

        assertThatCode(() -> throttle.assertNotThrottled(EMAIL, NOW)).doesNotThrowAnyException();
    }

    @Test
    void withinTheFreeAttempts_thereIsNoDelayAtAll() {
        // Someone mistyping their own password must never meet this feature.
        givenFailures(FREE_ATTEMPTS, NOW);

        assertThatCode(() -> throttle.assertNotThrottled(EMAIL, NOW)).doesNotThrowAnyException();
    }

    @Test
    void theFirstFailurePastTheFreeOnes_costsTheBaseDelay() {
        givenFailures(FREE_ATTEMPTS + 1, NOW);

        assertThatThrownBy(() -> throttle.assertNotThrottled(EMAIL, NOW))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .extracting(ex -> ((TooManyLoginAttemptsException) ex).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(BASE_DELAY_SECONDS));
    }

    @Test
    void eachFurtherFailure_doublesTheDelay() {
        assertThat(retryAfterWith(FREE_ATTEMPTS + 1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(retryAfterWith(FREE_ATTEMPTS + 2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(retryAfterWith(FREE_ATTEMPTS + 3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(retryAfterWith(FREE_ATTEMPTS + 4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void theDelayIsCapped() {
        assertThat(retryAfterWith(FREE_ATTEMPTS + 20)).isEqualTo(Duration.ofSeconds(MAX_DELAY_SECONDS));
    }

    /**
     * A long-running attack pushes the count arbitrarily high. Doubling by
     * shifting means an unclamped exponent wraps at 64 and produces a
     * <em>shorter</em> delay than the attempt before it - the throttle would
     * quietly release exactly the address being attacked hardest.
     */
    @Test
    void anAbsurdFailureCount_staysAtTheCapRatherThanWrappingAround() {
        assertThat(retryAfterWith(1_000)).isEqualTo(Duration.ofSeconds(MAX_DELAY_SECONDS));
        assertThat(retryAfterWith(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(MAX_DELAY_SECONDS));
    }

    @Test
    void waitingOutTheDelay_isEnoughToBeLetBackIn() {
        // The window runs from the last failure, so time alone always clears
        // it - nothing has to unlock anything.
        givenFailures(FREE_ATTEMPTS + 3, NOW.minusSeconds(8));

        assertThatCode(() -> throttle.assertNotThrottled(EMAIL, NOW)).doesNotThrowAnyException();
    }

    @Test
    void aCounterOlderThanTheResetWindow_isTreatedAsSpent() {
        // Yesterday's bad day costs nothing today, even at a count whose
        // delay would otherwise still be running.
        givenFailures(FREE_ATTEMPTS + 20, NOW.minusSeconds(RESET_AFTER_SECONDS + 1));

        assertThatCode(() -> throttle.assertNotThrottled(EMAIL, NOW)).doesNotThrowAnyException();
    }

    @Test
    void recordFailure_advancesTheCounterAndPassesTheResetBoundary() {
        throttle.recordFailure(EMAIL, NOW);

        verify(loginAttemptRepository).recordFailure(
                eq(EMAIL), eq(NOW), eq(NOW.minusSeconds(RESET_AFTER_SECONDS)));
    }

    @Test
    void recordSuccess_clearsTheCounter() {
        // Otherwise someone who finally remembers their password is still
        // serving out a delay they earned before they did.
        throttle.recordSuccess(EMAIL);

        verify(loginAttemptRepository).clear(EMAIL);
    }

    private Duration retryAfterWith(int failedCount) {
        givenFailures(failedCount, NOW);
        return catchThrowableOfType(
                () -> throttle.assertNotThrottled(EMAIL, NOW), TooManyLoginAttemptsException.class).getRetryAfter();
    }

    /** {@code LoginAttempt} is only ever written by the upsert, so it has no setters. */
    private void givenFailures(int failedCount, Instant lastFailureAt) {
        // The no-arg constructor is protected (JPA only), so it is not
        // callable from this package directly.
        LoginAttempt attempt = BeanUtils.instantiateClass(LoginAttempt.class);
        ReflectionTestUtils.setField(attempt, "email", EMAIL);
        ReflectionTestUtils.setField(attempt, "failedCount", failedCount);
        ReflectionTestUtils.setField(attempt, "lastFailureAt", lastFailureAt);
        when(loginAttemptRepository.findById(EMAIL)).thenReturn(Optional.of(attempt));
    }
}
