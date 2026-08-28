package com.portfolio.banking.account.exception;

/**
 * Thrown when an operation conflicts with the current state of a resource:
 * a concurrent update lost the optimistic-locking race, an account is frozen
 * and cannot be debited, a duplicate idempotency key was reused with a
 * different payload, etc. Services translate this into an HTTP 409.
 */
public class ConflictException extends BankingException {

    private static final String ERROR_CODE = "CONFLICT";

    public ConflictException(String message) {
        super(ERROR_CODE, message);
    }

    protected ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
