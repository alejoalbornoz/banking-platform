package com.portfolio.banking.notification.service;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.notification.client.IAccountClient;
import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.exception.ForbiddenException;
import com.portfolio.banking.notification.mapper.INotificationMapper;
import com.portfolio.banking.notification.model.Notification;
import com.portfolio.banking.notification.model.ProcessedEvent;
import com.portfolio.banking.notification.repository.INotificationRepository;
import com.portfolio.banking.notification.repository.IProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The consumer-side mirror of account-service's idempotent ledger.
 * <p>
 * There, a unique constraint on {@code (account_id, operation_key)} stopped
 * the same client request from moving money twice. Here, a unique constraint
 * on {@code processed_events.event_id} stops the same broker message -
 * redelivered after a crash, a requeue, or just RabbitMQ's at-least-once
 * guarantee - from creating the same notification twice. Same tool, opposite
 * end of the pipe: there we protected a write API from a repeated request,
 * here we protect an event handler from a repeated delivery.
 */
@Service
public class NotificationService implements INotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final IProcessedEventRepository processedEventRepository;
    private final INotificationRepository notificationRepository;
    private final INotificationMapper notificationMapper;
    private final TransactionTemplate transactionTemplate;
    private final IAccountClient accountClient;

    public NotificationService(IProcessedEventRepository processedEventRepository,
                                INotificationRepository notificationRepository,
                                INotificationMapper notificationMapper,
                                TransactionTemplate transactionTemplate,
                                IAccountClient accountClient) {
        this.processedEventRepository = processedEventRepository;
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
        this.transactionTemplate = transactionTemplate;
        this.accountClient = accountClient;
    }

    @Override
    public void handleAccountCreated(AccountCreatedEvent event) {
        processIdempotently(event.getEventId(), () -> List.of(Notification.forAccountCreated(event)));
    }

    @Override
    public void handleTransferCompleted(TransferCompletedEvent event) {
        processIdempotently(event.getEventId(), () -> List.of(
                Notification.forTransferSent(event),
                Notification.forTransferReceived(event)));
    }

    @Override
    public void handleTransferFailed(TransferFailedEvent event) {
        processIdempotently(event.getEventId(), () -> List.of(Notification.forTransferFailed(event)));
    }

    /**
     * Deliberately not {@code @Transactional}: {@code accountClient.getAccount}
     * is a network call, and a database transaction must never stay open
     * across one - see "How a transfer works" in the README for why. The
     * repository call below runs in its own transaction regardless (Spring
     * Data JPA wraps every repository method that way on its own), so no
     * explicit annotation is needed here for that part to be correct.
     */
    @Override
    public List<NotificationResponse> listForAccount(String callerId, UUID accountId) {
        var account = accountClient.getAccount(accountId);
        if (!callerId.equals(account.ownerId().toString())) {
            throw new ForbiddenException("Not authorized to view notifications for this account");
        }
        return notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(notificationMapper::toResponse)
                .toList();
    }

    /**
     * Marks {@code eventId} processed and creates its notifications
     * atomically, in one local transaction - both happen or neither does.
     * <p>
     * The catch has to sit <em>outside</em> the transaction, not inside the
     * same callback around just the insert. Postgres aborts a transaction on
     * the first failed statement; catching the violation and trying to carry
     * on in the same transaction would just fail again on the very next
     * statement. Letting {@code TransactionTemplate} roll the whole thing
     * back first, then catching the exception it rethrows, is what makes it
     * safe to simply treat "already processed" as nothing left to do here.
     * <p>
     * This is deliberately not wrapped in a retry template the way the
     * ledger's optimistic-lock conflicts are. That retried a race between two
     * different operations that both deserved to be applied. Here, a
     * constraint violation always means the exact same event was already
     * handled - there is no winner's outcome to adopt, nothing to redo, and
     * no caller waiting on a response. It's just a no-op.
     */
    private void processIdempotently(UUID eventId, Supplier<List<Notification>> notificationsToCreate) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                processedEventRepository.saveAndFlush(new ProcessedEvent(eventId));
                List<Notification> notifications = notificationsToCreate.get();
                notificationRepository.saveAll(notifications);
                notifications.forEach(n -> log.info("Notification [{}] to account {}: {}",
                        n.getType(), n.getRecipientAccountId(), n.getMessage()));
            });
        } catch (DataIntegrityViolationException alreadyProcessed) {
            log.info("Event {} already processed - skipping redelivery", eventId);
        }
    }
}
