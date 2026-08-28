package com.portfolio.banking.account.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error shape returned by every service, so a frontend client only
 * needs to write one error-handling path regardless of which service it hit.
 *
 * @param fieldErrors populated for bean-validation failures; empty otherwise
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String errorCode,
        String message,
        String path,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }
}
