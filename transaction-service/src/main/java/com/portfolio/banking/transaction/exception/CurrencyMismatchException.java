package com.portfolio.banking.transaction.exception;

/**
 * Thrown when a transfer's currency doesn't match one of the two accounts'
 * own currency - a USD transfer can't touch a EUR account, and this project
 * doesn't do FX conversion. Accounts never change currency after creation,
 * so unlike insufficient funds (which depends on a balance that changes over
 * time), this can never succeed on a later retry of the same request - it's
 * rejected before the saga starts, and no transaction row is ever created
 * for it. Translated into an HTTP 400.
 */
public class CurrencyMismatchException extends BankingException {

    private static final String ERROR_CODE = "CURRENCY_MISMATCH";

    public CurrencyMismatchException(String message) {
        super(ERROR_CODE, message);
    }
}
