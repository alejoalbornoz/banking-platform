package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface IAccountService {

    AccountResponse createAccount(CreateAccountRequest request);

    AccountResponse getAccount(UUID accountId);

    AccountResponse getAccountByNumber(String accountNumber);

    List<AccountResponse> listAccountsByOwner(UUID ownerId);

    /**
     * Credits (adds funds to) an account. Safe to call concurrently with
     * other operations on the same account: internally retries on
     * optimistic-locking conflicts.
     */
    AccountResponse credit(UUID accountId, BigDecimal amount);

    /**
     * Debits (removes funds from) an account. Throws
     * {@link com.portfolio.banking.account.exception.InsufficientFundsException}
     * if the balance is too low, and retries internally on optimistic-locking
     * conflicts.
     */
    AccountResponse debit(UUID accountId, BigDecimal amount);
}
