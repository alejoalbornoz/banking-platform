package com.portfolio.banking.transaction.exception;

/**
 * Raised when account-service reports insufficient funds for a debit.
 * Not retryable - the balance won't change by itself, so the saga should
 * fail immediately rather than spend retry attempts on it.
 */
public class InsufficientFundsException extends ConflictException {

    private static final String ERROR_CODE = "INSUFFICIENT_FUNDS";

    public InsufficientFundsException(String message) {
        super(ERROR_CODE, message);
    }
}
