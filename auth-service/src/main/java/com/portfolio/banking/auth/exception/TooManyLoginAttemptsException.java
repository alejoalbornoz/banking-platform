package com.portfolio.banking.auth.exception;

import java.time.Duration;

/**
 * Thrown when an address has failed too many logins too recently. Translated
 * into an HTTP 429 carrying a {@code Retry-After} header.
 * <p>
 * Note what this does <em>not</em> leak. It is raised for any address that has
 * been failing, registered or not, so being told to wait says only "somebody
 * has been getting this address's password wrong" - never "this address
 * exists". Throttling only real accounts would have turned the throttle
 * itself into the user-enumeration oracle that {@link
 * InvalidCredentialsException} exists to avoid.
 */
public class TooManyLoginAttemptsException extends BankingException {

    private static final String ERROR_CODE = "TOO_MANY_LOGIN_ATTEMPTS";

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super(ERROR_CODE, "Too many failed login attempts. Try again in "
                + Math.max(1, retryAfter.toSeconds()) + " seconds.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
