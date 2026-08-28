package com.portfolio.banking.account.mapper;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.model.Account;

public interface IAccountMapper {

    AccountResponse toResponse(Account account);
}
