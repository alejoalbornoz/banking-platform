package com.portfolio.banking.transaction.model;

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
 * A single transfer request and the state of its saga.
 * <p>
 * {@code idempotencyKey} has a unique DB constraint: if a client retries the
 * same request (same key), the second INSERT fails with a constraint
 * violation, which the service layer catches and turns into "look up and
 * return the existing result" instead of a duplicate transfer.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private UUID destinationAccountId;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TransactionStatus status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {
        // required by JPA
    }

    public Transaction(String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId,
                        BigDecimal amount, String currency) {
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = TransactionStatus.PENDING;
    }

    /** True if {@code other} describes the exact same transfer this row already represents. */
    public boolean matches(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency) {
        return this.sourceAccountId.equals(sourceAccountId)
                && this.destinationAccountId.equals(destinationAccountId)
                && this.amount.compareTo(amount) == 0
                && this.currency.equals(currency);
    }

    public void markDebited() {
        this.status = TransactionStatus.DEBITED;
    }

    public void markCompleted() {
        this.status = TransactionStatus.COMPLETED;
    }

    public void markFailed(String reason) {
        this.status = TransactionStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    public void markCompensationFailed(String reason) {
        this.status = TransactionStatus.COMPENSATION_FAILED;
        this.failureReason = truncate(reason);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getSourceAccountId() {
        return sourceAccountId;
    }

    public UUID getDestinationAccountId() {
        return destinationAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
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
