package com.portfolio.banking.account.exception;

/**
 * Thrown when a requested entity (account, transaction, user, ...) cannot be
 * found. Services translate this into an HTTP 404 at the controller layer.
 */
public class ResourceNotFoundException extends BankingException {

    private static final String ERROR_CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(ERROR_CODE, message);
    }

    public static ResourceNotFoundException forEntity(String entityName, Object identifier) {
        return new ResourceNotFoundException(entityName + " not found with identifier: " + identifier);
    }
}
