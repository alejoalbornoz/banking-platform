package com.portfolio.banking.notification.exception;

/**
 * Thrown when a requested entity cannot be found. Translated into an HTTP
 * 404 at the controller layer.
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
