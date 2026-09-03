package com.portfolio.banking.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable posting against an account. Every balance change produces
 * exactly one of these, in the same transaction as the balance change itself.
 * <p>
 * Nothing here is updatable, deliberately: a ledger is append-only. Correcting
 * a mistaken entry means posting a compensating entry in the other direction,
 * never editing or deleting history - that's what makes the trail auditable.
 * <p>
 * {@code operationKey} is the caller-supplied idempotency key, unique per
 * account. Its uniqueness is enforced by the database rather than by a
 * check-then-insert in Java, because only the database can make that check
 * atomic against a concurrent request doing the same thing.
 * <p>
 * {@code balanceAfter} snapshots the account balance immediately after this
 * posting. It's redundant with the running sum of entries - which is exactly
 * the point: the two must agree, so storing both turns silent corruption into
 * something detectable.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    /** Operation key used for the entry that records an account's opening balance. */
    public static final String OPENING_OPERATION_KEY = "opening";

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "operation_key", nullable = false, updatable = false, length = 255)
    private String operationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private LedgerDirection direction;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // required by JPA
    }

    public LedgerEntry(UUID accountId, String operationKey, LedgerDirection direction,
                        BigDecimal amount, String currency, BigDecimal balanceAfter) {
        this.accountId = accountId;
        this.operationKey = operationKey;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.balanceAfter = balanceAfter;
    }

    /**
     * The entry explaining where an account's starting balance came from, so
     * that "balance equals the sum of its entries" holds from the moment the
     * account exists rather than only for money that moved later.
     */
    public static LedgerEntry opening(UUID accountId, BigDecimal openingBalance, String currency) {
        return new LedgerEntry(accountId, OPENING_OPERATION_KEY, LedgerDirection.CREDIT,
                openingBalance, currency, openingBalance);
    }

    /**
     * True if {@code other} describes the same posting this entry already
     * recorded. Used to tell a legitimate retry (same key, same operation -
     * replay the result) apart from a key being reused for something else
     * (same key, different operation - refuse).
     */
    public boolean matches(LedgerDirection direction, BigDecimal amount) {
        return this.direction == direction && this.amount.compareTo(amount) == 0;
    }

    /** This entry's contribution to the account's balance: positive for a credit, negative for a debit. */
    public BigDecimal signedAmount() {
        return direction.signed(amount);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
