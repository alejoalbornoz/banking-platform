package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.OutboxEvent;

/**
 * Raises the alert for an event the relay has stopped trying to publish.
 * <p>
 * Dead-lettering without telling anyone would only trade one silent failure
 * for another: before, a hopeless event blocked the outbox invisibly; parking
 * it quietly would instead lose it invisibly, which is worse - the system
 * would look healthy while a state change that already committed never
 * reached its consumers.
 * <p>
 * A seam, like {@link IStuckTransferAlerter}: the shipped implementation
 * emits a counter and a marked log line, and a real deployment substitutes
 * whatever it actually pages with.
 * <p>
 * Implementations must not throw. The row is already dead by the time this
 * runs, and an unreachable alerting channel must not take the relay's next
 * poll down with it.
 */
public interface IDeadLetteredEventAlerter {

    /**
     * @param event    the event that will not be delivered
     * @param attempts how many publish attempts it took before giving up
     * @param reason   the last failure, which is usually the whole diagnosis
     */
    void alert(OutboxEvent event, int attempts, String reason);
}
