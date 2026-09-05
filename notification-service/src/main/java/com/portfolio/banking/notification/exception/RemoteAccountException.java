package com.portfolio.banking.notification.exception;

/** account-service returned an error other than "not found" while checking account ownership. */
public class RemoteAccountException extends ConflictException {

    private static final String ERROR_CODE = "REMOTE_ACCOUNT_ERROR";

    public RemoteAccountException(String message) {
        super(ERROR_CODE, message);
    }
}
