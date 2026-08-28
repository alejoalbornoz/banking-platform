package com.portfolio.banking.transaction.client;

import java.math.BigDecimal;
import java.util.UUID;

public interface IAccountClient {

    /**
     * @throws com.portfolio.banking.transaction.exception.InsufficientFundsException not enough balance
     * @throws com.portfolio.banking.transaction.exception.ResourceNotFoundException  account doesn't exist
     * @throws com.portfolio.banking.transaction.exception.RemoteConcurrencyException account-service's own retries were exhausted
     */
    void debit(UUID accountId, BigDecimal amount);

    /**
     * @throws com.portfolio.banking.transaction.exception.ResourceNotFoundException  account doesn't exist
     * @throws com.portfolio.banking.transaction.exception.RemoteConcurrencyException account-service's own retries were exhausted
     */
    void credit(UUID accountId, BigDecimal amount);
}
