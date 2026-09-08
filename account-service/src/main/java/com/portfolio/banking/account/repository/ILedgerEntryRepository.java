package com.portfolio.banking.account.repository;

import com.portfolio.banking.account.model.LedgerDirection;
import com.portfolio.banking.account.model.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ILedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * The idempotency lookup: has this operation already been posted to this
     * account? Backed by the {@code (account_id, operation_key)} unique index,
     * so it stays a single index hit no matter how long an account's history
     * grows.
     */
    Optional<LedgerEntry> findByAccountIdAndOperationKey(UUID accountId, String operationKey);

    /** First page of an account statement: newest posting first. */
    @Query("""
            SELECT e FROM LedgerEntry e
            WHERE e.accountId = :accountId
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<LedgerEntry> findFirstPageByAccountId(@Param("accountId") UUID accountId, Pageable pageable);

    /** The page after the row identified by {@code (afterCreatedAt, afterId)}. */
    @Query("""
            SELECT e FROM LedgerEntry e
            WHERE e.accountId = :accountId
              AND (e.createdAt < :afterCreatedAt
                   OR (e.createdAt = :afterCreatedAt AND e.id < :afterId))
            ORDER BY e.createdAt DESC, e.id DESC
            """)
    List<LedgerEntry> findPageByAccountIdAfter(@Param("accountId") UUID accountId,
                                                @Param("afterCreatedAt") Instant afterCreatedAt,
                                                @Param("afterId") UUID afterId,
                                                Pageable pageable);

    /**
     * The reconciliation total: every entry on the account, credits positive
     * and debits negative, summed by the database.
     * <p>
     * This has to be its own query now that the statement is paginated. The
     * balance used to be recomputed in Java by summing the entries in the
     * response, which was correct only while the response contained all of
     * them - once it holds twenty rows out of thousands, that sum stops being
     * a reconciliation and becomes a page subtotal that disagrees with the
     * stored balance on every account with more than one page. Reconciliation
     * is a statement about the whole ledger, so it has to be computed over the
     * whole ledger.
     *
     * @return null for an account with no entries at all, which is a real
     *         state (an account opened at zero) rather than a missing value -
     *         the caller reads it as zero.
     */
    @Query("""
            SELECT SUM(CASE WHEN e.direction = :creditDirection THEN e.amount ELSE -e.amount END)
            FROM LedgerEntry e
            WHERE e.accountId = :accountId
            """)
    BigDecimal sumSignedAmountByAccountId(@Param("accountId") UUID accountId,
                                           @Param("creditDirection") LedgerDirection creditDirection);
}
