package com.portfolio.banking.notification.service;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.notification.client.IAccountClient;
import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.projection.ProjectionRunner;
import com.portfolio.banking.notification.exception.ForbiddenException;
import com.portfolio.banking.notification.mapper.INotificationMapper;
import com.portfolio.banking.notification.model.Notification;
import com.portfolio.banking.notification.repository.INotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    /**
     * The name under which this projection records what it has handled. Part
     * of the stored key, so it is a contract: renaming it would make every
     * event look new again.
     */
    static final String PROJECTION = "notifications";

    private final ProjectionRunner projectionRunner;
    private final INotificationRepository notificationRepository;
    private final INotificationMapper notificationMapper;
    private final IAccountClient accountClient;

    public NotificationService(ProjectionRunner projectionRunner,
                                INotificationRepository notificationRepository,
                                INotificationMapper notificationMapper,
                                IAccountClient accountClient) {
        this.projectionRunner = projectionRunner;
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
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
    public PageResponse<NotificationResponse> listForAccount(String callerId, UUID accountId, KeysetPage page) {
        var account = accountClient.getAccount(accountId);
        if (!callerId.equals(account.ownerId().toString())) {
            throw new ForbiddenException("Not authorized to view notifications for this account");
        }

        List<Notification> fetched = page.isFirstPage()
                ? notificationRepository.findFirstPageByRecipientAccountId(accountId, page.limitOnly())
                : notificationRepository.findPageByRecipientAccountIdAfter(
                        accountId, page.afterCreatedAt(), page.afterId(), page.limitOnly());

        return page.build(fetched, notificationMapper::toResponse,
                Notification::getCreatedAt, Notification::getId);
    }

    /**
     * Idempotency lives in {@link ProjectionRunner} now, shared with the
     * movements projection; what is left here is only what this projection
     * writes for an event.
     */
    private void processIdempotently(UUID eventId, Supplier<List<Notification>> notificationsToCreate) {
        projectionRunner.runOnce(PROJECTION, eventId, () -> {
            List<Notification> notifications = notificationsToCreate.get();
            notificationRepository.saveAll(notifications);
            notifications.forEach(n -> log.info("Notification [{}] to account {}: {}",
                    n.getType(), n.getRecipientAccountId(), n.getMessage()));
        });
    }
}
