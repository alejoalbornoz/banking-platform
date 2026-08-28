package com.portfolio.banking.transaction.exception;

public class RemoteAccountException extends ConflictException {

    private static final String ERROR_CODE = "REMOTE_ACCOUNT_ERROR";

    public RemoteAccountException(String message) {
        super(ERROR_CODE, message);
    }
}
