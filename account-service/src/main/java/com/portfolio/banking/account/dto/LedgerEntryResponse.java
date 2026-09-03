package com.portfolio.banking.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of an account statement.
 *
 * @param operationKey the idempotency key the caller supplied for this
 *                      posting. Exposed on purpose: it's how a client can
 *                      confirm whether an operation it isn't sure about
 *                      actually landed.
 * @param balanceAfter the account balance immediately after this posting
 */
public record LedgerEntryResponse(
        UUID id,
        String operationKey,
        String direction,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        Instant createdAt
) {
}
