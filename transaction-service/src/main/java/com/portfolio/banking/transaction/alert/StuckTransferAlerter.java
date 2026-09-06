package com.portfolio.banking.transaction.alert;

import com.portfolio.banking.transaction.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Component;

/**
 * Alerts on the two channels an operations setup actually watches: a metric
 * to alert <em>on</em>, and a log line to read once the alert fires.
 * <p>
 * The counter is the part that pages someone. It's exposed through Actuator
 * (`/actuator/metrics/banking.transfers.stuck`, and `/actuator/prometheus`
 * for scraping), where the alert rule is a one-liner - any increase at all is
 * an incident, since the correct value is permanently zero. That's the whole
 * point of counting it rather than only writing a log line: "money is stuck"
 * becomes something a monitoring system notices on its own, instead of
 * something a human has to already suspect before going to look.
 * <p>
 * Deliberately untagged. The obvious instinct is to tag by transaction id so
 * the metric says <em>which</em> transfer broke, but ids are unbounded and
 * every distinct tag value creates its own time series - that's how a metrics
 * backend gets taken down by a cardinality explosion. The identifiers belong
 * in the log line below (and in {@code GET /api/v1/transfers/stuck}); the
 * metric only has to answer "is anything stuck, and how often".
 */
@Component
public class StuckTransferAlerter implements IStuckTransferAlerter {

    private static final Logger log = LoggerFactory.getLogger(StuckTransferAlerter.class);

    /**
     * Lets a log pipeline route or alert on this event by category instead of
     * by matching message text, which anyone could reword. Note that Spring
     * Boot's default console encoder doesn't print markers - the message is
     * written to be unmistakable on its own for local use, while structured
     * appenders (JSON encoders, log shippers) can match the marker itself.
     */
    private static final Marker OPS_ALERT = MarkerFactory.getMarker("OPS_ALERT");

    static final String STUCK_TRANSFERS_COUNTER = "banking.transfers.stuck";

    private final Counter stuckTransfers;

    public StuckTransferAlerter(MeterRegistry meterRegistry) {
        this.stuckTransfers = Counter.builder(STUCK_TRANSFERS_COUNTER)
                .description("Transfers whose compensation also failed, leaving money stuck mid-transfer. "
                        + "Should always be zero; any increase is an incident.")
                .register(meterRegistry);
    }

    @Override
    public void alert(Transaction transaction, String creditFailure, String compensationFailure) {
        stuckTransfers.increment();
        log.error(OPS_ALERT,
                "STUCK TRANSFER - manual intervention required. {} {} left account {} and reached nobody. "
                        + "transactionId={} destinationAccountId={} creditFailure=[{}] compensationFailure=[{}]",
                transaction.getAmount(), transaction.getCurrency(), transaction.getSourceAccountId(),
                transaction.getId(), transaction.getDestinationAccountId(),
                creditFailure, compensationFailure);
    }
}
