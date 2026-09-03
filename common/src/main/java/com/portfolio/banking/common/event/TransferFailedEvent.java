package com.portfolio.banking.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a transfer ends unsuccessfully after money had already
 * started moving - either the destination credit failed and the source was
 * compensated (FAILED), or the compensation itself failed and funds are stuck
 * (COMPENSATION_FAILED).
 * <p>
 * Note what is deliberately NOT published: a transfer that fails on the very
 * first debit (insufficient funds, frozen account). Nothing external happened
 * there, so there is no state for a consumer to reconcile - the caller already
 * got a synchronous error. Only failures with real side effects are announced.
 * <p>
 * {@code reason} is human-readable and meant for notifications and ops
 * dashboards; consumers should branch on the routing key or on the
 * transaction's own status, never by parsing this string.
 */
public class TransferFailedEvent extends DomainEvent {

    private final UUID transactionId;
    private final UUID sourceAccountId;
    private final UUID destinationAccountId;
    private final BigDecimal amount;
    private final String currency;
    private final String reason;

    /** Used by a publisher creating a brand new event: mints a fresh eventId/occurredAt. */
    public TransferFailedEvent(UUID transactionId, UUID sourceAccountId, UUID destinationAccountId,
                                BigDecimal amount, String currency, String reason) {
        this(UUID.randomUUID(), Instant.now(), transactionId, sourceAccountId, destinationAccountId,
                amount, currency, reason);
    }

    /**
     * Used by Jackson to reconstruct this event on the consumer side, preserving
     * the original {@code eventId} so redeliveries can be deduplicated. See
     * {@link AccountCreatedEvent}'s matching constructor for why this exists.
     */
    @JsonCreator
    public TransferFailedEvent(@JsonProperty("eventId") UUID eventId,
                                @JsonProperty("occurredAt") Instant occurredAt,
                                @JsonProperty("transactionId") UUID transactionId,
                                @JsonProperty("sourceAccountId") UUID sourceAccountId,
                                @JsonProperty("destinationAccountId") UUID destinationAccountId,
                                @JsonProperty("amount") BigDecimal amount,
                                @JsonProperty("currency") String currency,
                                @JsonProperty("reason") String reason) {
        super(eventId, occurredAt);
        this.transactionId = transactionId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
    }

    public UUID getTransactionId() {
        return transactionId;
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

    public String getReason() {
        return reason;
    }
}
