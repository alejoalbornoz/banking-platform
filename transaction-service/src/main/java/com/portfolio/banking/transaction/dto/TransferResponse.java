package com.portfolio.banking.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
