package com.portfolio.banking.account.exception;

/**
 * Thrown when the authenticated caller is a real, valid principal but isn't
 * allowed to act on the specific resource being requested - e.g. reading an
 * account that isn't theirs. Distinct from an authentication failure (which
 * Spring Security's own filter chain rejects before a controller ever runs);
 * this is an authorization failure, decided by comparing the resource's
 * owner to the caller. Translated into an HTTP 403.
 */
public class ForbiddenException extends BankingException {

    private static final String ERROR_CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(ERROR_CODE, message);
    }
}
