package com.portfolio.banking.notification.projection;

import com.portfolio.banking.notification.model.ProcessedEvent;
import com.portfolio.banking.notification.repository.IProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Runs one projection's handling of one event exactly once, however many
 * times the event is delivered.
 * <p>
 * This used to be a private method of {@code NotificationService}. It is a
 * component now because there are two projections over the same stream, and
 * "exactly once per projection" is the property that keeps them independent:
 * each records its own {@code (event, projection)} row, in the same
 * transaction as its own writes, so a redelivery skips the one that already
 * committed and retries only the one that did not. A single shared
 * "processed" flag would make the first projection's success hide the
 * second's failure forever.
 * <p>
 * The mechanism is unchanged and still deliberately not a check-then-act:
 * {@code saveAndFlush} on the marker row raises the primary-key violation
 * inside the transaction, where it can be caught, rather than at commit.
 * The catch sits <em>outside</em> the transaction because Postgres aborts a
 * transaction on the first failed statement - nothing after the violation
 * could succeed in it anyway, so the whole thing rolls back and "already
 * handled" is simply nothing left to do.
 */
@Component
public class ProjectionRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRunner.class);

    private final IProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    public ProjectionRunner(IProcessedEventRepository processedEventRepository,
                             TransactionTemplate transactionTemplate) {
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * @param projection a stable name for the projection - it is part of the
     *                    stored key, so renaming one means it re-handles
     *                    everything
     * @param work       the projection's writes for this event; runs in the
     *                    same transaction as the marker row, so both commit or
     *                    neither does
     * @return true if this call did the work, false if it had already been done
     */
    public boolean runOnce(String projection, UUID eventId, Runnable work) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, projection));
                work.run();
            });
            return true;
        } catch (DataIntegrityViolationException alreadyProcessed) {
            log.info("Event {} already handled by projection '{}' - skipping redelivery", eventId, projection);
            return false;
        }
    }
}
