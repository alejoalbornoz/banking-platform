package com.portfolio.banking.notification.model;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a statement, from one account's point of view, built from an
 * event rather than from account-service's ledger.
 * <p>
 * The ledger is the source of truth for balances and already lists every
 * posting on an account. What it cannot say is <em>who the other side was</em>
 * - a ledger entry carries an operation key, not a counterparty - and it
 * cannot be read across all of one person's accounts without first knowing
 * which accounts those are. This row carries both, because the events that
 * produce it do.
 * <p>
 * {@code ownerId} is nullable: the transfer that produces a movement can
 * arrive before the {@code account.created} that says who the account belongs
 * to. Nothing about RabbitMQ, or about two independent publishers, guarantees
 * otherwise. The row is written without an owner and claimed when the
 * ownership arrives - see {@code MovementProjector}.
 */
@Entity
@Table(name = "movements")
public class Movement {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private MovementKind kind;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "counterparty_account_id", updatable = false)
    private UUID counterpartyAccountId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    /**
     * When the money moved, taken from the event - not when this row was
     * written. A projection that lags by an hour must still order a Monday
     * transfer before a Tuesday one.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected Movement() {
        // required by JPA
    }

    private Movement(UUID eventId, UUID accountId, UUID ownerId, MovementKind kind, BigDecimal amount,
                      String currency, UUID counterpartyAccountId, UUID transactionId, Instant occurredAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.ownerId = ownerId;
        this.kind = kind;
        this.amount = amount;
        this.currency = currency;
        this.counterpartyAccountId = counterpartyAccountId;
        this.transactionId = transactionId;
        this.occurredAt = occurredAt;
    }

    /** The opening balance. Owner known: it is in the same event. */
    public static Movement opening(AccountCreatedEvent event) {
        return new Movement(event.getEventId(), event.getAccountId(), event.getOwnerId(), MovementKind.OPENING,
                event.getOpeningBalance(), event.getCurrency(), null, null, event.getOccurredAt());
    }

    /** @param ownerId who owns the source, if this service knows yet; null otherwise */
    public static Movement sent(TransferCompletedEvent event, UUID ownerId) {
        return new Movement(event.getEventId(), event.getSourceAccountId(), ownerId, MovementKind.SENT,
                event.getAmount(), event.getCurrency(), event.getDestinationAccountId(),
                event.getTransactionId(), event.getOccurredAt());
    }

    /** @param ownerId who owns the destination, if this service knows yet; null otherwise */
    public static Movement received(TransferCompletedEvent event, UUID ownerId) {
        return new Movement(event.getEventId(), event.getDestinationAccountId(), ownerId, MovementKind.RECEIVED,
                event.getAmount(), event.getCurrency(), event.getSourceAccountId(),
                event.getTransactionId(), event.getOccurredAt());
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public MovementKind getKind() {
        return kind;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getCounterpartyAccountId() {
        return counterpartyAccountId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
