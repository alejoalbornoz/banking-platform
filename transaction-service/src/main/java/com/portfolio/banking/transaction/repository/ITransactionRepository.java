package com.portfolio.banking.transaction.repository;

import com.portfolio.banking.transaction.model.Transaction;
import com.portfolio.banking.transaction.model.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** First page of the transfers one user started, newest first. */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.initiatedBy = :initiatedBy
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<Transaction> findFirstPageByInitiatedBy(@Param("initiatedBy") UUID initiatedBy, Pageable pageable);

    /**
     * The page after the row identified by {@code (afterCreatedAt, afterId)}.
     * Ordered by both columns so the resume position is unique - see
     * {@code KeysetPage}.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.initiatedBy = :initiatedBy
              AND (t.createdAt < :afterCreatedAt
                   OR (t.createdAt = :afterCreatedAt AND t.id < :afterId))
            ORDER BY t.createdAt DESC, t.id DESC
            """)
    List<Transaction> findPageByInitiatedByAfter(@Param("initiatedBy") UUID initiatedBy,
                                                  @Param("afterCreatedAt") Instant afterCreatedAt,
                                                  @Param("afterId") UUID afterId,
                                                  Pageable pageable);
}
