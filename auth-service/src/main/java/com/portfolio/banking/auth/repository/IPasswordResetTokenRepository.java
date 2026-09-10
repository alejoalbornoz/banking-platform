package com.portfolio.banking.auth.repository;

import com.portfolio.banking.auth.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IPasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Spends a token, and says whether this caller is the one that spent it.
     * <p>
     * The same conditional UPDATE as refresh-token consumption, for the same
     * reason: a check-then-act cannot arbitrate a race it is part of. Two
     * requests presenting one token both read {@code used_at IS NULL} and
     * both proceed, and the second one would reset the password again - to
     * whatever the second request asked for.
     *
     * @return 1 if this call spent it, 0 if it was already spent
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PasswordResetToken t
               SET t.usedAt = :now
             WHERE t.id = :id
               AND t.usedAt IS NULL
            """)
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Invalidates whatever this user still has outstanding.
     * <p>
     * Called when a new reset is requested, so several live tokens never
     * exist at once - and after any password change, since a reset token
     * issued before the change would otherwise still be spendable by whoever
     * asked for it.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE PasswordResetToken t
               SET t.usedAt = :now
             WHERE t.userId = :userId
               AND t.usedAt IS NULL
            """)
    int invalidateOutstandingFor(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Drops tokens long past their (short) expiry, so the table stays small. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
