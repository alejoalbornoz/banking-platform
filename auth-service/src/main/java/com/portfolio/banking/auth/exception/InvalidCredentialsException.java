package com.portfolio.banking.auth.exception;

/**
 * Thrown for a failed login or service-token request: unknown email/client
 * id, or a password/secret that doesn't match. Deliberately doesn't say
 * which - "email not found" vs "wrong password" tells an attacker which
 * emails are registered. Translated into an HTTP 401.
 */
public class InvalidCredentialsException extends BankingException {

    private static final String ERROR_CODE = "INVALID_CREDENTIALS";

    public InvalidCredentialsException() {
        super(ERROR_CODE, "Invalid credentials");
    }
}
