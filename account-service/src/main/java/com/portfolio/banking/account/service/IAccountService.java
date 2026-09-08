package com.portfolio.banking.account.service;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface IAccountService {

    AccountResponse createAccount(UUID ownerId, CreateAccountRequest request);

    AccountResponse getAccount(UUID accountId);

    AccountResponse getAccountByNumber(String accountNumber);

    List<AccountResponse> listAccountsByOwner(UUID ownerId);

    /**
     * Credits (adds funds to) an account, exactly once per
     * {@code operationKey}.
     * <p>
     * Safe to call concurrently with other operations on the same account
     * (it retries internally on optimistic-locking conflicts) and safe to
     * call repeatedly with the same key: the second call posts nothing and
     * returns the account's current state.
     *
     * @param operationKey caller-supplied idempotency key, unique per account
     * @throws com.portfolio.banking.account.exception.OperationKeyReusedException
     *         if {@code operationKey} already posted a different operation to
     *         this account
     */
    AccountResponse credit(UUID accountId, String operationKey, BigDecimal amount);

    /**
     * Debits (removes funds from) an account, exactly once per
     * {@code operationKey}. Same idempotency and concurrency guarantees as
     * {@link #credit}.
     *
     * @throws com.portfolio.banking.account.exception.InsufficientFundsException
     *         if the balance is too low
     * @throws com.portfolio.banking.account.exception.OperationKeyReusedException
     *         if {@code operationKey} already posted a different operation to
     *         this account
     */
    AccountResponse debit(UUID accountId, String operationKey, BigDecimal amount);

    /**
     * The account's full statement, plus a recomputed balance so the caller
     * can see that the stored balance and the ledger agree.
     */
    LedgerResponse getLedger(UUID accountId);

    /**
     * Blocks outgoing transfers while still accepting incoming ones. Safe to
     * repeat - unlike credit/debit, asking for a state the account is
     * already in changes nothing, so these three need no idempotency key.
     *
     * @throws com.portfolio.banking.account.exception.ConflictException if the account is closed
     */
    AccountResponse freeze(UUID accountId);

    /**
     * @throws com.portfolio.banking.account.exception.ConflictException if the account is closed
     */
    AccountResponse reactivate(UUID accountId);

    /**
     * Final: a closed account can't be reopened, debited, or credited.
     *
     * @throws com.portfolio.banking.account.exception.ConflictException if the balance isn't zero
     */
    AccountResponse close(UUID accountId);
}
