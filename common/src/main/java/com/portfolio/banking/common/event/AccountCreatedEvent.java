package com.portfolio.banking.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published after an account is successfully created and committed.
 * Consumed by the Notifications service to send a welcome message, and
 * potentially by an Audit service for compliance logging.
 */
public class AccountCreatedEvent extends DomainEvent {

    private final UUID accountId;
    private final String accountNumber;
    private final UUID ownerId;
    private final BigDecimal openingBalance;
    private final String currency;

    /** Used by a publisher creating a brand new event: mints a fresh eventId/occurredAt. */
    public AccountCreatedEvent(UUID accountId, String accountNumber, UUID ownerId,
                                BigDecimal openingBalance, String currency) {
        this(UUID.randomUUID(), Instant.now(), accountId, accountNumber, ownerId, openingBalance, currency);
    }

    /**
     * Used by Jackson to reconstruct this event on the consumer side.
     * <p>
     * Without this constructor, deserialization would fall back to the one
     * above - the only one Jackson could otherwise find - and mint a brand
     * new {@code eventId} for every redelivery, since {@code eventId} and
     * {@code occurredAt} aren't among its parameters. That would silently
     * break consumer-side deduplication: every retry of the same message
     * would look like a new event instead of the one it actually is.
     */
    @JsonCreator
    public AccountCreatedEvent(@JsonProperty("eventId") UUID eventId,
                                @JsonProperty("occurredAt") Instant occurredAt,
                                @JsonProperty("accountId") UUID accountId,
                                @JsonProperty("accountNumber") String accountNumber,
                                @JsonProperty("ownerId") UUID ownerId,
                                @JsonProperty("openingBalance") BigDecimal openingBalance,
                                @JsonProperty("currency") String currency) {
        super(eventId, occurredAt);
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.openingBalance = openingBalance;
        this.currency = currency;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public String getCurrency() {
        return currency;
    }
}
