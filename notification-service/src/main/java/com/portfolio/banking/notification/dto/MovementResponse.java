package com.portfolio.banking.notification.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One statement line, with the two things the ledger cannot give: who the
 * other side was, and which transfer it belongs to.
 *
 * @param counterpartyAccountId the other account in a transfer; null for an
 *                               opening balance, which has no other side
 * @param transactionId          the transfer, for cross-referencing with
 *                               {@code GET /api/v1/transfers/{id}}; null for
 *                               an opening balance
 */
public record MovementResponse(
        UUID id,
        UUID accountId,
        String kind,
        BigDecimal amount,
        String currency,
        UUID counterpartyAccountId,
        UUID transactionId,
        Instant occurredAt
) {
}
