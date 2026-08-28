package com.portfolio.banking.transaction.exception;

/**
 * An idempotency key must always describe the same operation. If a client
 * sends the same key with a different source/destination/amount/currency,
 * that's not a safe retry - it's ambiguous which request the client
 * actually wants, so we refuse rather than silently pick one.
 */
public class IdempotencyKeyReusedException extends ConflictException {

    private static final String ERROR_CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(ERROR_CODE, "Idempotency-Key '" + idempotencyKey
                + "' was already used for a different transfer request");
    }
}
