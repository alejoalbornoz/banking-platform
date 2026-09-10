package com.portfolio.banking.transaction.repository;

import com.portfolio.banking.transaction.model.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IOutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * What the relay polls: rows it can still do something about, oldest
     * first. Dead rows are excluded here rather than skipped in Java, because
     * they would otherwise occupy the head of every batch permanently and
     * starve everything behind them.
     */
    List<OutboxEvent> findByPublishedFalseAndDeadAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    /**
     * One more failed attempt against this specific row.
     * <p>
     * An UPDATE rather than a {@code save()} of the entity the relay is
     * holding: that entity was loaded in a transaction that has since rolled
     * back, so it is detached and its {@code attempts} is a snapshot.
     * Incrementing in SQL means the stored value is what advances, whatever
     * the caller happens to be holding.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
               SET e.attempts = e.attempts + 1,
                   e.lastError = :reason
             WHERE e.id = :id
            """)
    int recordFailure(@Param("id") UUID id, @Param("reason") String reason);

    /**
     * The last failure: record it and stop retrying, in one statement so a
     * row can never be left counted-but-not-dead.
     * <p>
     * {@code deadAt IS NULL} keeps it idempotent - re-running it would
     * otherwise move the timestamp of a death that already happened.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEvent e
               SET e.attempts = e.attempts + 1,
                   e.lastError = :reason,
                   e.deadAt = :now
             WHERE e.id = :id
               AND e.deadAt IS NULL
            """)
    int recordFailureAndMarkDead(@Param("id") UUID id,
                                  @Param("reason") String reason,
                                  @Param("now") Instant now);

    /** The backlog: how much is still waiting to go out. */
    long countByPublishedFalseAndDeadAtIsNull();

    /** Everything the relay has given up on. Ops question, not a hot path. */
    List<OutboxEvent> findByDeadAtIsNotNullOrderByCreatedAtAsc();
}
