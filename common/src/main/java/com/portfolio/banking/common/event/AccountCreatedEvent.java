package com.portfolio.banking.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published after an account is successfully created and committed.
 * Consumed later by the Notifications service to send a welcome message,
 * and potentially by an Audit service for compliance logging.
 */
public class AccountCreatedEvent extends DomainEvent {

    private final UUID accountId;
    private final String accountNumber;
    private final UUID ownerId;
    private final BigDecimal openingBalance;
    private final String currency;

    public AccountCreatedEvent(UUID accountId, String accountNumber, UUID ownerId,
                                BigDecimal openingBalance, String currency) {
        super();
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
