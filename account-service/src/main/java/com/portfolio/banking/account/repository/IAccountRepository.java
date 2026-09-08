package com.portfolio.banking.account.repository;

import com.portfolio.banking.account.model.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IAccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * First page of one owner's accounts, newest first.
     * <p>
     * Ordered by {@code (createdAt, id)} rather than {@code createdAt} alone
     * so that the position the next page resumes from is unique - see
     * {@code KeysetPage}.
     */
    @Query("""
            SELECT a FROM Account a
            WHERE a.ownerId = :ownerId
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Account> findFirstPageByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    /**
     * The page after the row identified by {@code (afterCreatedAt, afterId)}.
     * <p>
     * The disjunction is the keyset comparison written out: strictly older
     * rows, plus the rows sharing the cursor's timestamp that sort after it by
     * id. It cannot be expressed as a derived query method - Spring Data
     * combines conditions left to right without the grouping this needs - so
     * it is spelled out here instead.
     */
    @Query("""
            SELECT a FROM Account a
            WHERE a.ownerId = :ownerId
              AND (a.createdAt < :afterCreatedAt
                   OR (a.createdAt = :afterCreatedAt AND a.id < :afterId))
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Account> findPageByOwnerIdAfter(@Param("ownerId") UUID ownerId,
                                          @Param("afterCreatedAt") Instant afterCreatedAt,
                                          @Param("afterId") UUID afterId,
                                          Pageable pageable);
}
