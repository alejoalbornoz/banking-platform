package com.portfolio.banking.account.mapper;

import com.portfolio.banking.account.dto.LedgerEntryResponse;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.dto.PageResponse;
import com.portfolio.banking.account.model.Account;
import com.portfolio.banking.account.model.LedgerEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class LedgerMapper implements ILedgerMapper {

    @Override
    public LedgerEntryResponse toResponse(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getOperationKey(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }

    @Override
    public LedgerResponse toLedgerResponse(Account account,
                                            BigDecimal computedBalance,
                                            PageResponse<LedgerEntryResponse> entries) {
        return new LedgerResponse(
                account.getId(),
                account.getBalance(),
                computedBalance,
                // compareTo, not equals: BigDecimal.equals("100.0") is false
                // against "100.00" because it compares scale too, which would
                // make a perfectly reconciled account report as broken.
                account.getBalance().compareTo(computedBalance) == 0,
                entries
        );
    }
}
