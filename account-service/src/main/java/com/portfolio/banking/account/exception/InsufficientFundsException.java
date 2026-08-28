package com.portfolio.banking.account.exception;

import java.math.BigDecimal;

/**
 * Thrown when a debit would take an account's balance below zero (or below
 * its allowed overdraft, once that concept is introduced).
 */
public class InsufficientFundsException extends ConflictException {

    private static final String ERROR_CODE = "INSUFFICIENT_FUNDS";

    public InsufficientFundsException(String accountNumber, BigDecimal available, BigDecimal requested) {
        super(ERROR_CODE, "Account " + accountNumber + " has insufficient funds: available="
                + available + ", requested=" + requested);
    }
}
