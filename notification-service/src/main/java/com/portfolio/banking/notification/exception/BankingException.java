package com.portfolio.banking.notification.exception;

/**
 * Base type for all business exceptions in the banking platform.
 * <p>
 * Each subtype carries a stable {@code errorCode} that is independent of the
 * transport layer (HTTP status, message queue error, etc.) so that any
 * service exposing this exception can map it consistently, and so that
 * clients can branch on the code instead of parsing free-text messages.
 */
public abstract class BankingException extends RuntimeException {

    private final String errorCode;

    protected BankingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
