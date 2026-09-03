package com.portfolio.banking.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published once both legs of a transfer have succeeded and the transaction
 * row is COMPLETED.
 * <p>
 * Unlike {@link AccountCreatedEvent}, this one is never published directly by
 * the service that produces it. transaction-service writes it to its outbox
 * table inside the same transaction that flips the status to COMPLETED, and a
 * relay publishes it afterwards - so the event and the state change can never
 * disagree. See transaction-service's {@code OutboxEvent} for why.
 * <p>
 * Consumers must treat this as at-least-once: the relay can crash between
 * publishing to RabbitMQ and marking the row published, in which case the
 * next poll republishes it. Deduplicate on {@code eventId}.
 */
public class TransferCompletedEvent extends DomainEvent {

    private final UUID transactionId;
    private final UUID sourceAccountId;
    private final UUID destinationAccountId;
    private final BigDecimal amount;
    private final String currency;

    /** Used by a publisher creating a brand new event: mints a fresh eventId/occurredAt. */
    public TransferCompletedEvent(UUID transactionId, UUID sourceAccountId, UUID destinationAccountId,
                                   BigDecimal amount, String currency) {
        this(UUID.randomUUID(), Instant.now(), transactionId, sourceAccountId, destinationAccountId, amount, currency);
    }

    /**
     * Used by Jackson to reconstruct this event on the consumer side, preserving
     * the original {@code eventId} so redeliveries can be deduplicated. See
     * {@link AccountCreatedEvent}'s matching constructor for why this exists.
     */
    @JsonCreator
    public TransferCompletedEvent(@JsonProperty("eventId") UUID eventId,
                                   @JsonProperty("occurredAt") Instant occurredAt,
                                   @JsonProperty("transactionId") UUID transactionId,
                                   @JsonProperty("sourceAccountId") UUID sourceAccountId,
                                   @JsonProperty("destinationAccountId") UUID destinationAccountId,
                                   @JsonProperty("amount") BigDecimal amount,
                                   @JsonProperty("currency") String currency) {
        super(eventId, occurredAt);
        this.transactionId = transactionId;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.currency = currency;
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
}
