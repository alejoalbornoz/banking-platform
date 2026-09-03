package com.portfolio.banking.notification.model;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification that would be sent to an account holder. Since this
 * portfolio project has no real email/SMS provider to hand off to, the
 * "send" is standing in for that: the row itself is the durable record of
 * what the recipient would have been told, and {@code BankingEventListener}
 * logs it at the point it's created.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private UUID recipientAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private NotificationType type;

    @Column(nullable = false, updatable = false, length = 500)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // required by JPA
    }

    public Notification(UUID eventId, UUID recipientAccountId, NotificationType type, String message) {
        this.eventId = eventId;
        this.recipientAccountId = recipientAccountId;
        this.type = type;
        this.message = message;
    }

    public static Notification forAccountCreated(AccountCreatedEvent event) {
        return new Notification(event.getEventId(), event.getAccountId(), NotificationType.ACCOUNT_CREATED,
                "Your account " + event.getAccountNumber() + " was opened with an opening balance of "
                        + event.getOpeningBalance() + " " + event.getCurrency() + ".");
    }

    public static Notification forTransferSent(TransferCompletedEvent event) {
        return new Notification(event.getEventId(), event.getSourceAccountId(), NotificationType.TRANSFER_SENT,
                "You sent " + event.getAmount() + " " + event.getCurrency() + " to account "
                        + event.getDestinationAccountId() + ".");
    }

    public static Notification forTransferReceived(TransferCompletedEvent event) {
        return new Notification(event.getEventId(), event.getDestinationAccountId(), NotificationType.TRANSFER_RECEIVED,
                "You received " + event.getAmount() + " " + event.getCurrency() + " from account "
                        + event.getSourceAccountId() + ".");
    }

    /**
     * Only the source account is notified. The destination never received
     * anything - nothing happened on their side to tell them about.
     */
    public static Notification forTransferFailed(TransferFailedEvent event) {
        return new Notification(event.getEventId(), event.getSourceAccountId(), NotificationType.TRANSFER_FAILED,
                "Your transfer of " + event.getAmount() + " " + event.getCurrency() + " to account "
                        + event.getDestinationAccountId() + " failed: " + event.getReason() + ".");
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getRecipientAccountId() {
        return recipientAccountId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
