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

    /**
     * Blocks outgoing money while still accepting incoming - see
     * {@link #credit} and {@link #debit}. Freezing an already-frozen account
     * is a no-op rather than an error: the caller asked for a state, and
     * that state already holds.
     */
    public void freeze() {
        requireNotClosed("freeze");
        this.status = AccountStatus.FROZEN;
    }

    public void reactivate() {
        requireNotClosed("reactivate");
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Requires a zero balance: closing an account that still holds money
     * would strand it, since a closed account can be neither debited nor
     * credited afterwards.
     */
    public void close() {
        if (this.balance.signum() != 0) {
            throw new ConflictException("Cannot close account " + accountNumber + " with a non-zero balance: " + balance);
        }
        this.status = AccountStatus.CLOSED;
    }

    /**
     * CLOSED is terminal. Without this, reactivating a closed account would
     * silently undo a deliberate, final decision - and it would do so
     * through a method whose name suggests nothing of the sort. The rule
     * lives here rather than in a service because it is a property of the
     * account itself, in the same way "balance never goes negative" is.
     */
    private void requireNotClosed(String operation) {
        if (status == AccountStatus.CLOSED) {
            throw new ConflictException(
                    "Cannot " + operation + " a closed account: " + accountNumber + " (closing is final)");
        }
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
