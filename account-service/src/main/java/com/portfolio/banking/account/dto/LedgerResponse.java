package com.portfolio.banking.account.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An account statement plus the proof that it adds up.
 *
 * @param storedBalance   what {@code accounts.balance} currently says
 * @param computedBalance the sum of every ledger entry, recomputed on read
 * @param reconciled      whether those two agree. They always should: both are
 *                         written in the same transaction. Surfacing the check
 *                         instead of assuming it means a bug that breaks the
 *                         invariant shows up as {@code false} here rather than
 *                         as quietly wrong money.
 */
public record LedgerResponse(
        UUID accountId,
        BigDecimal storedBalance,
        BigDecimal computedBalance,
        boolean reconciled,
        List<LedgerEntryResponse> entries
) {
}
