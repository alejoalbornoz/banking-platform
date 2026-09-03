package com.portfolio.banking.account.mapper;

import com.portfolio.banking.account.dto.LedgerEntryResponse;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.model.LedgerEntry;

import java.util.List;

public interface ILedgerMapper {

    LedgerEntryResponse toResponse(LedgerEntry entry);

    /**
     * Builds a statement for {@code account}, recomputing the balance from
     * {@code entries} so the response can report whether the stored balance
     * and the ledger agree.
     */
    LedgerResponse toLedgerResponse(Account account, List<LedgerEntry> entries);
}
