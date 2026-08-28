package com.portfolio.banking.transaction.exception;

/**
 * account-service already retries optimistic-locking conflicts internally
 * (see its own RetryTemplate). If it still returns CONCURRENT_UPDATE, the
 * account was under heavy write contention for a whole retry cycle - still
 * worth one more retry from here, since the contention is very likely to
 * have cleared by the time we ask again.
 */
public class RemoteConcurrencyException extends ConflictException {

    private static final String ERROR_CODE = "REMOTE_CONCURRENT_UPDATE";

    public RemoteConcurrencyException(String message) {
        super(ERROR_CODE, message);
    }
}
