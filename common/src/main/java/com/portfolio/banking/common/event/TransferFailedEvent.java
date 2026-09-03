package com.portfolio.banking.common.event;

import java.math.BigDecimal;
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

    public TransferFailedEvent(UUID transactionId, UUID sourceAccountId, UUID destinationAccountId,
                                BigDecimal amount, String currency, String reason) {
        super();
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
