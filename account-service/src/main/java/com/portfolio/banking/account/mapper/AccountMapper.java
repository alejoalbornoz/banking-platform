package com.portfolio.banking.account.mapper;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.model.Account;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper implements IAccountMapper {

    @Override
    public AccountResponse toResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getOwnerId(),
                account.getBalance(),
                account.getCurrency(),
                account.getStatus().name(),
                account.getVersion(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
