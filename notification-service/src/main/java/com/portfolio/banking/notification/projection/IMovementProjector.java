package com.portfolio.banking.notification.projection;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;

/**
 * The write side of the movements read model: turns the events this service
 * already consumes into statement lines, idempotently per event.
 * <p>
 * Deliberately a second projection alongside notifications rather than more
 * code inside it. They consume the same stream and each records its own
 * {@code (event, projection)} marker, so a redelivery skips whichever of the
 * two already committed and retries only the other - and a bug in one can
 * never roll back the other.
 */
public interface IMovementProjector {

    /**
     * Records who owns the account, claims any movements that arrived before
     * this did, and writes the opening balance as the first movement if
     * there is one.
     */
    void project(AccountCreatedEvent event);

    /**
     * Writes SENT on the source and RECEIVED on the destination. Either
     * account's owner may still be unknown to this service; the rows are
     * written regardless and claimed later.
     */
    void project(TransferCompletedEvent event);
}
