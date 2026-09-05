package com.portfolio.banking.notification.exception;

/**
 * Thrown when an operation conflicts with the current state of a resource,
 * or when a downstream call fails in a way that isn't more specifically
 * classified (see {@link RemoteAccountException}). Translated into an HTTP
 * 409.
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
