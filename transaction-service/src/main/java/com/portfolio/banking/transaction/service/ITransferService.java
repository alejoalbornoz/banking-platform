package com.portfolio.banking.transaction.service;

import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;

import java.util.UUID;

public interface ITransferService {

    /**
     * Executes (or, for a retried request, replays the result of) a
     * transfer between two accounts. Safe to call repeatedly with the same
     * {@code idempotencyKey} and the same {@code request}: after the first
     * call, subsequent calls return the stored result without re-executing
     * anything against account-service.
     *
     * @throws com.portfolio.banking.transaction.exception.IdempotencyKeyReusedException
     *         if {@code idempotencyKey} was already used for a different request
     */
    TransferResponse transfer(String idempotencyKey, TransferRequest request);

    TransferResponse getTransaction(UUID transactionId);
}
