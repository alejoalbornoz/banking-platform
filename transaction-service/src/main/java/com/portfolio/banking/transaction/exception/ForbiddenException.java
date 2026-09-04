package com.portfolio.banking.transaction.exception;

/**
 * Thrown when the authenticated caller doesn't own the account(s) involved
 * in the transfer being initiated or looked up. Translated into an HTTP 403.
 */
public class ForbiddenException extends BankingException {

    private static final String ERROR_CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(ERROR_CODE, message);
    }
}
