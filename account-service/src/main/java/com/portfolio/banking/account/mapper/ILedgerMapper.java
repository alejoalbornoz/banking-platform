package com.portfolio.banking.account.mapper;

import com.portfolio.banking.account.dto.LedgerEntryResponse;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.dto.PageResponse;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.model.LedgerEntry;

import java.math.BigDecimal;

public interface ILedgerMapper {

    LedgerEntryResponse toResponse(LedgerEntry entry);

    /**
     * Builds a statement for {@code account} from one page of its entries and
     * the total the database computed over all of them.
     * <p>
     * {@code computedBalance} is a parameter rather than something derived
     * here on purpose: the entries this receives are a page, and reconciliation
     * is a claim about the entire ledger. Summing what happens to be on this
     * page would turn a correctness check into a coincidence that holds only
     * for accounts short enough to fit in one response.
     */
    LedgerResponse toLedgerResponse(Account account,
                                     BigDecimal computedBalance,
                                     PageResponse<LedgerEntryResponse> entries);
}
