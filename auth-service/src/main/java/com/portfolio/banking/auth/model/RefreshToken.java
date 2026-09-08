package com.portfolio.banking.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token, stored as a hash and consumable exactly once.
 * <p>
 * The state machine is deliberately tiny: a token is created unused and
 * unrevoked, and exactly one of two things then happens to it. Either it is
 * <em>used</em> - exchanged for a new access token and a successor in the
 * same family - or the family it belongs to is <em>revoked</em>, by an
 * explicit logout or by reuse detection. Neither transition ever reverses.
 * <p>
 * Nothing here checks whether a token may be consumed. That decision cannot
 * be made by reading a row, because two requests can read the same
 * unused row at the same time and both conclude yes - see
 * {@code IRefreshTokenRepository.consume}, where a conditional UPDATE lets
 * the database decide instead. The accessors below answer questions that
 * are safe to ask of a snapshot, and no more.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    /**
     * SHA-256 of the token, hex-encoded. The token itself is returned to the
     * caller once, at issue time, and never stored anywhere.
     */
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Shared by every token descended from one login. Rotation keeps the
     * family and replaces the token, so revoking the family invalidates the
     * entire chain - including successors an attacker may already hold.
     */
    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
        // required by JPA
    }

    public RefreshToken(String tokenHash, UUID userId, UUID familyId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    /**
     * Expiry is a fact about the clock, not a race: a token that has expired
     * stays expired, so a stale read can never wrongly report yes here.
     */
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Only ever true-to-false-proof in one direction: a revoked token stays
     * revoked. A snapshot saying "not revoked" may be out of date, which is
     * why the conditional UPDATE re-checks it rather than trusting this.
     */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
