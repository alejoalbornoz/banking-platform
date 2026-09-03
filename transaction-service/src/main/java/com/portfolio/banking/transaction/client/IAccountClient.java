package com.portfolio.banking.transaction.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Our view of account-service's balance operations.
 * <p>
 * Both calls take an {@code operationKey}, which account-service treats as an
 * idempotency key scoped to that account. This is what makes retrying safe:
 * without it, a call that timed out after actually committing would be
 * indistinguishable from one that never arrived, and retrying would move the
 * money twice. The key is per account, so a transfer's two legs can share a
 * transaction id without colliding.
 */
public interface IAccountClient {

    /**
     * @param operationKey idempotency key; retrying with the same key posts nothing new
     * @throws com.portfolio.banking.transaction.exception.InsufficientFundsException not enough balance
     * @throws com.portfolio.banking.transaction.exception.ResourceNotFoundException  account doesn't exist
     * @throws com.portfolio.banking.transaction.exception.RemoteConcurrencyException account-service's own retries were exhausted
     */
    void debit(UUID accountId, String operationKey, BigDecimal amount);

    /**
     * @param operationKey idempotency key; retrying with the same key posts nothing new
     * @throws com.portfolio.banking.transaction.exception.ResourceNotFoundException  account doesn't exist
     * @throws com.portfolio.banking.transaction.exception.RemoteConcurrencyException account-service's own retries were exhausted
     */
    void credit(UUID accountId, String operationKey, BigDecimal amount);
}
