package com.portfolio.banking.account.model;

import com.portfolio.banking.account.exception.ConflictException;
import com.portfolio.banking.account.exception.InsufficientFundsException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A bank account: the single source of truth for how much money it holds.
 * <p>
 * Concurrency: {@code version} is mapped with {@link Version}, so Hibernate
 * adds {@code WHERE id = ? AND version = ?} to every UPDATE and bumps the
 * version on success. If two transactions load the same account and both
 * try to commit a change, the second one hits zero affected rows and
 * Hibernate raises {@link jakarta.persistence.OptimisticLockException},
 * which the service layer translates into a {@code ConcurrentUpdateException}
 * for the caller to retry. This is deliberately optimistic (no DB row lock
 * held during the transaction) because most transfers don't collide, and
 * optimistic locking scales far better than pessimistic locking under that
 * assumption. See the service layer for how retries are handled.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false, length = 34)
    private String accountNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // required by JPA
    }

    public Account(String accountNumber, UUID ownerId, BigDecimal openingBalance, String currency) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.balance = openingBalance;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Increases the balance. Allowed regardless of status except CLOSED,
     * since a frozen account can still legitimately receive funds (e.g. a
     * refund) even while outgoing transfers are blocked.
     */
    public void credit(BigDecimal amount) {
        requirePositiveAmount(amount);
        if (status == AccountStatus.CLOSED) {
            throw new ConflictException("Cannot credit a closed account: " + accountNumber);
        }
        this.balance = this.balance.add(amount);
    }

    /**
     * Decreases the balance. Requires the account to be ACTIVE and to have
     * sufficient funds. Throwing here (inside the aggregate) rather than in
     * the service keeps the invariant "balance never goes negative" co-located
     * with the state it protects, instead of relying on every caller to
     * remember to check first.
     */
    public void debit(BigDecimal amount) {
        requirePositiveAmount(amount);
        if (status != AccountStatus.ACTIVE) {
            throw new ConflictException("Cannot debit a non-active account: " + accountNumber + " (status=" + status + ")");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(accountNumber, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    private static void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    public void reactivate() {
        this.status = AccountStatus.ACTIVE;
    }

    public void close() {
        if (this.balance.signum() != 0) {
            throw new ConflictException("Cannot close account " + accountNumber + " with a non-zero balance: " + balance);
        }
        this.status = AccountStatus.CLOSED;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
