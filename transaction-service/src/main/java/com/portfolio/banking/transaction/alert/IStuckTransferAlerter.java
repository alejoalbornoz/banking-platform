package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.Transaction;

/**
 * Raises the one alert in this system that genuinely needs a human: a
 * transfer whose credit failed <em>and</em> whose compensating credit-back
 * also failed, so money has left the source account and reached nobody.
 * <p>
 * This exists as an interface because it's the seam a real deployment would
 * replace: {@link StuckTransferAlerter} emits a metric and a marked log line,
 * which is what log/metric-based alerting hooks onto, but swapping in a
 * pager, a Slack webhook, or an incident-management API is implementing this
 * one method. Nothing else in the flow changes.
 * <p>
 * Implementations should assume they're called <em>before</em> the
 * {@code COMPENSATION_FAILED} state has been persisted - see
 * {@code TransferService} for why that ordering is deliberate.
 */
public interface IStuckTransferAlerter {

    /**
     * @param transaction         the transfer that got stuck
     * @param creditFailure       why crediting the destination failed
     * @param compensationFailure why crediting the source back also failed
     */
    void alert(Transaction transaction, String creditFailure, String compensationFailure);
}
