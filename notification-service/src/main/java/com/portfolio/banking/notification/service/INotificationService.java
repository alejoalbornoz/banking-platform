package com.portfolio.banking.notification.service;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.notification.dto.NotificationResponse;

import java.util.List;
import java.util.UUID;

public interface INotificationService {

    /**
     * Idempotently records the notification(s) for one event. Safe to call
     * more than once with an event carrying the same {@code eventId}: every
     * call after the first is a no-op, since the message may have been
     * redelivered rather than genuinely repeated.
     */
    void handleAccountCreated(AccountCreatedEvent event);

    /** Notifies both parties: the sender that money left, the receiver that it arrived. */
    void handleTransferCompleted(TransferCompletedEvent event);

    /** Notifies only the source account - the destination never received anything. */
    void handleTransferFailed(TransferFailedEvent event);

    List<NotificationResponse> listForAccount(UUID accountId);
}
