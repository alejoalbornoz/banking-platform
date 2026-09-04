package com.portfolio.banking.auth.exception;

/**
 * Thrown when registration is attempted with an email that's already taken.
 * Translated into an HTTP 409 at the controller layer.
 */
public class EmailAlreadyExistsException extends BankingException {

    private static final String ERROR_CODE = "EMAIL_ALREADY_EXISTS";

    public EmailAlreadyExistsException(String email) {
        super(ERROR_CODE, "An account already exists for email: " + email);
    }
}
