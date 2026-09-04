package com.portfolio.banking.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Deliberately carries no owner identifier: the account being created always
 * belongs to whoever is authenticated, taken from the caller's JWT, not
 * something a client gets to assert about itself.
 *
 * @param openingBalance defaults to zero at the controller if not supplied
 * @param currency       ISO 4217 currency code, e.g. "USD"
 */
public record CreateAccountRequest(

        @NotNull(message = "openingBalance is required")
        @DecimalMin(value = "0.00", message = "openingBalance cannot be negative")
        BigDecimal openingBalance,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code, e.g. USD")
        String currency
) {
}
