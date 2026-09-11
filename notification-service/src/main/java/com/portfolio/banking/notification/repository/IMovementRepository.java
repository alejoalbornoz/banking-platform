package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.Movement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IMovementRepository extends JpaRepository<Movement, UUID> {

    /**
     * Claims the rows that were written before this service knew who owned
     * their account.
     * <p>
     * This is the whole answer to events arriving out of order. A completed
     * transfer that reaches this service before the {@code account.created}
     * for one of its accounts is projected anyway, with {@code owner_id}
     * null; when the ownership turns up, this UPDATE attaches everything
     * that was waiting for it. Nothing is dropped, nothing is retried, and
     * no consumer has to be paused until the other publisher catches up.
     */
    @Modifying
    @Query("""
            UPDATE Movement m
               SET m.ownerId = :ownerId
             WHERE m.accountId = :accountId
               AND m.ownerId IS NULL
            """)
    int claimUnowned(@Param("accountId") UUID accountId, @Param("ownerId") UUID ownerId);

    // --- the read side: one owner's statement, keyset on (occurredAt, id) ---

    @Query("""
            SELECT m FROM Movement m
            WHERE m.ownerId = :ownerId
              AND (:accountId IS NULL OR m.accountId = :accountId)
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<Movement> findFirstPageByOwner(@Param("ownerId") UUID ownerId,
                                         @Param("accountId") UUID accountId,
                                         Pageable pageable);

    @Query("""
            SELECT m FROM Movement m
            WHERE m.ownerId = :ownerId
              AND (:accountId IS NULL OR m.accountId = :accountId)
              AND (m.occurredAt < :afterOccurredAt
                   OR (m.occurredAt = :afterOccurredAt AND m.id < :afterId))
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<Movement> findPageByOwnerAfter(@Param("ownerId") UUID ownerId,
                                         @Param("accountId") UUID accountId,
                                         @Param("afterOccurredAt") Instant afterOccurredAt,
                                         @Param("afterId") UUID afterId,
                                         Pageable pageable);
}
