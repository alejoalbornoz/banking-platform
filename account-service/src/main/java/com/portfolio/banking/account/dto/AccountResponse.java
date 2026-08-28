package com.portfolio.banking.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        UUID ownerId,
        BigDecimal balance,
        String currency,
        String status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
}
