package com.portfolio.banking.transaction.exception;

public class ConflictException extends BankingException {

    private static final String ERROR_CODE = "CONFLICT";

    public ConflictException(String message) {
        super(ERROR_CODE, message);
    }

    protected ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
