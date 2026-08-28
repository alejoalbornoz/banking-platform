package com.portfolio.banking.transaction.exception;

public abstract class BankingException extends RuntimeException {

    private final String errorCode;

    protected BankingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected BankingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
