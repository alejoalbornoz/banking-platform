package com.portfolio.banking.auth.repository;

import com.portfolio.banking.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IRefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * The presented token, found by its hash. Possible only because the hash
     * is unsalted - a bcrypt-style salted hash can't be looked up at all,
     * only compared row by row against the whole table.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Consumes a token, and answers whether this caller is the one that got
     * to consume it.
     * <p>
     * This is the whole reuse-detection mechanism, and it is a conditional
     * UPDATE rather than a read followed by a write for the same reason
     * account-service's ledger leans on a unique constraint: a check-then-act
     * cannot arbitrate a race it is part of. Two requests presenting the same
     * token both read {@code used_at IS NULL} and both conclude they may
     * proceed. Only the database can decide, and the {@code WHERE} clause is
     * where it does.
     * <p>
     * {@code revoked_at IS NULL} is re-checked here rather than trusted from
     * the row that was read, so a family revoked concurrently - by a logout,
     * or by another request detecting reuse - is not consumed by a request
     * that read the row a moment earlier.
     *
     * @return 1 if this call consumed the token, 0 if it was already used or
     *         revoked. Zero means one of two callers is not the legitimate
     *         client, and there is no way to tell which - see
     *         {@code AuthService.refresh}.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RefreshToken t
               SET t.usedAt = :now
             WHERE t.id = :id
               AND t.usedAt IS NULL
               AND t.revokedAt IS NULL
            """)
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes every still-live token descended from one login. Used both by
     * an explicit logout and by reuse detection - the difference is only in
     * what prompted it, not in what has to happen.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now
             WHERE t.familyId = :familyId
               AND t.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Ends every session this user has anywhere, across all families.
     * <p>
     * What makes a password change worth anything. Revoking only the caller's
     * own family would leave whoever stole the password still holding a live
     * refresh token, quietly rotating it forever - the account would keep
     * being theirs after the owner had already "fixed" it.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now
             WHERE t.userId = :userId
               AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Drops rows that expired long enough ago to be of no further use, so the
     * table doesn't grow for the lifetime of the deployment. Deleting them
     * the moment they expire would be a mistake: a reuse attempt against a
     * just-expired token should still find its row and read as an expired
     * token rather than as an unknown one.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
