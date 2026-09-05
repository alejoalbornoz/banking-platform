package com.portfolio.banking.notification.exception;

/**
 * Thrown when the authenticated caller isn't the account's owner. Translated
 * into an HTTP 403.
 */
public class ForbiddenException extends BankingException {

    private static final String ERROR_CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(ERROR_CODE, message);
    }
}
