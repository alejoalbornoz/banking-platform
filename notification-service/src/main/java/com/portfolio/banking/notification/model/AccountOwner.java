package com.portfolio.banking.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Who owns which account, as this service has heard it.
 * <p>
 * A projection of {@code account.created}, and the reason the movements read
 * needs no call to account-service: the ownership every other read here has
 * to ask for over HTTP is, for this one, already local. It also means the
 * answer is exactly as current as the last event that arrived - which for
 * ownership is fine, since accounts here never change hands.
 * <p>
 * {@link Persistable} for the same reason as {@code ProcessedEvent}: the id
 * is assigned (it is the account's own id), and without {@code isNew()}
 * returning true Spring Data would issue a {@code merge} that silently
 * overwrites a redelivered row instead of the {@code persist} whose
 * constraint violation the caller relies on.
 */
@Entity
@Table(name = "account_owners")
public class AccountOwner implements Persistable<UUID> {

    @Id
    @Column(name = "account_id", updatable = false, nullable = false)
    private UUID accountId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @CreationTimestamp
    @Column(name = "projected_at", nullable = false, updatable = false)
    private Instant projectedAt;

    protected AccountOwner() {
        // required by JPA
    }

    public AccountOwner(UUID accountId, UUID ownerId, String currency) {
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.currency = currency;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
