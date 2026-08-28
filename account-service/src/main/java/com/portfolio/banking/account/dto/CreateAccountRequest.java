package com.portfolio.banking.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param ownerId        id of the account holder (will point at the Auth
 *                        service's user id once that service exists)
 * @param openingBalance defaults to zero at the controller if not supplied
 * @param currency       ISO 4217 currency code, e.g. "USD"
 */
public record CreateAccountRequest(

        @NotNull(message = "ownerId is required")
        UUID ownerId,

        @NotNull(message = "openingBalance is required")
        @DecimalMin(value = "0.00", message = "openingBalance cannot be negative")
        BigDecimal openingBalance,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code, e.g. USD")
        String currency
) {
}
