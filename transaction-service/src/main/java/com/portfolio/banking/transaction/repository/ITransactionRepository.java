package com.portfolio.banking.transaction.repository;

import com.portfolio.banking.transaction.model.Transaction;
import com.portfolio.banking.transaction.model.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ITransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Used for the ops view of stuck transfers. Deliberately unpaginated: the
     * only status queried this way is COMPENSATION_FAILED, whose correct size
     * is zero - if this ever returns enough rows for paging to matter, the
     * paging is not the problem.
     */
    List<Transaction> findAllByStatus(TransactionStatus status);
}
