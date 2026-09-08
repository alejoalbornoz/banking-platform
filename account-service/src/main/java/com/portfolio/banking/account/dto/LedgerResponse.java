package com.portfolio.banking.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One page of an account statement, plus the proof that the whole statement
 * adds up.
 *
 * @param storedBalance   what {@code accounts.balance} currently says
 * @param computedBalance the sum of every ledger entry on the account,
 *                         computed by the database over all of them - not by
 *                         summing {@code entries}, which holds one page. A
 *                         page subtotal would disagree with the stored balance
 *                         on any account long enough to paginate, and would
 *                         report a perfectly healthy ledger as broken.
 * @param reconciled      whether those two agree. They always should: both are
 *                         written in the same transaction. Surfacing the check
 *                         instead of assuming it means a bug that breaks the
 *                         invariant shows up as {@code false} here rather than
 *                         as quietly wrong money.
 * @param entries         newest posting first, with a cursor for the next page
 */
public record LedgerResponse(
        UUID accountId,
        BigDecimal storedBalance,
        BigDecimal computedBalance,
        boolean reconciled,
        PageResponse<LedgerEntryResponse> entries
) {
}
