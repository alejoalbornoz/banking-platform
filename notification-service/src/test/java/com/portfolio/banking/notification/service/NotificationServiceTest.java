package com.portfolio.banking.notification.service;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.common.event.TransferFailedEvent;
import com.portfolio.banking.notification.client.IAccountClient;
import com.portfolio.banking.notification.client.dto.AccountView;
import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.exception.ForbiddenException;
import com.portfolio.banking.notification.mapper.NotificationMapper;
import com.portfolio.banking.notification.model.Notification;
import com.portfolio.banking.notification.model.NotificationType;
import com.portfolio.banking.notification.model.ProcessedEvent;
import com.portfolio.banking.notification.repository.INotificationRepository;
import com.portfolio.banking.notification.repository.IProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private IProcessedEventRepository processedEventRepository;

    @Mock
    private INotificationRepository notificationRepository;

    @Mock
    private IAccountClient accountClient;

    private NotificationService notificationService;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("50.00");

    @BeforeEach
    void setUp() {
        var notificationMapper = new NotificationMapper();

        // Real TransactionTemplate backed by a mocked PlatformTransactionManager:
        // getTransaction/commit/rollback are no-ops, but the callback still runs
        // for real, so the atomic mark-processed-then-notify behavior is
        // exercised exactly as in production.
        //
        // lenient(): listForAccount doesn't use transactionTemplate at all
        // (deliberately - see its own comment), so it never touches this stub.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        notificationService = new NotificationService(
                processedEventRepository, notificationRepository, notificationMapper, transactionTemplate, accountClient);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Notification>> notificationListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void handleAccountCreated_newEvent_marksProcessedAndCreatesOneNotification() {
        AccountCreatedEvent event = new AccountCreatedEvent(
                UUID.randomUUID(), "123456789012", UUID.randomUUID(), new BigDecimal("100.00"), "USD");
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.handleAccountCreated(event);

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).saveAndFlush(processedCaptor.capture());
        assertThat(processedCaptor.getValue().getEventId()).isEqualTo(event.getEventId());

        ArgumentCaptor<List<Notification>> notificationsCaptor = notificationListCaptor();
        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        Notification notification = notificationsCaptor.getValue().get(0);
        assertThat(notification.getEventId()).isEqualTo(event.getEventId());
        assertThat(notification.getRecipientAccountId()).isEqualTo(event.getAccountId());
        assertThat(notification.getType()).isEqualTo(NotificationType.ACCOUNT_CREATED);
    }

    @Test
    void handleAccountCreated_redeliveredEvent_isNoOp() {
        // Simulates a message redelivered after it was already processed and
        // acked once - the exact scenario at-least-once delivery eventually
        // produces.
        AccountCreatedEvent event = new AccountCreatedEvent(
                UUID.randomUUID(), "123456789012", UUID.randomUUID(), new BigDecimal("100.00"), "USD");
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        notificationService.handleAccountCreated(event);

        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    void handleTransferCompleted_notifiesBothSenderAndReceiver() {
        TransferCompletedEvent event = new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, amount, "USD");
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.handleTransferCompleted(event);

        ArgumentCaptor<List<Notification>> notificationsCaptor = notificationListCaptor();
        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        List<Notification> notifications = notificationsCaptor.getValue();
        assertThat(notifications).hasSize(2);
        assertThat(notifications).anySatisfy(n -> {
            assertThat(n.getRecipientAccountId()).isEqualTo(sourceId);
            assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_SENT);
        });
        assertThat(notifications).anySatisfy(n -> {
            assertThat(n.getRecipientAccountId()).isEqualTo(destinationId);
            assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_RECEIVED);
        });
    }

    @Test
    void handleTransferFailed_notifiesOnlyTheSourceAccount() {
        TransferFailedEvent event = new TransferFailedEvent(
                UUID.randomUUID(), sourceId, destinationId, amount, "USD", "destination account not found");
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        notificationService.handleTransferFailed(event);

        ArgumentCaptor<List<Notification>> notificationsCaptor = notificationListCaptor();
        verify(notificationRepository).saveAll(notificationsCaptor.capture());
        assertThat(notificationsCaptor.getValue()).hasSize(1);
        Notification notification = notificationsCaptor.getValue().get(0);
        assertThat(notification.getRecipientAccountId()).isEqualTo(sourceId);
        assertThat(notification.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
        assertThat(notification.getMessage()).contains("destination account not found");
    }

    @Test
    void listForAccount_callerOwnsAccount_returnsMappedNotifications() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(accountClient.getAccount(accountId)).thenReturn(new AccountView(accountId, ownerId));
        Notification notification = new Notification(
                UUID.randomUUID(), accountId, NotificationType.ACCOUNT_CREATED, "Your account was opened.");
        when(notificationRepository.findAllByRecipientAccountIdOrderByCreatedAtDesc(accountId))
                .thenReturn(List.of(notification));

        List<NotificationResponse> responses = notificationService.listForAccount(ownerId.toString(), accountId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).recipientAccountId()).isEqualTo(accountId);
        assertThat(responses.get(0).type()).isEqualTo("ACCOUNT_CREATED");
    }

    @Test
    void listForAccount_callerDoesNotOwnAccount_throwsForbiddenAndNeverQueriesNotifications() {
        UUID accountId = UUID.randomUUID();
        when(accountClient.getAccount(accountId)).thenReturn(new AccountView(accountId, UUID.randomUUID()));
        String someoneElse = UUID.randomUUID().toString();

        assertThatThrownBy(() -> notificationService.listForAccount(someoneElse, accountId))
                .isInstanceOf(ForbiddenException.class);
        verify(notificationRepository, never()).findAllByRecipientAccountIdOrderByCreatedAtDesc(any());
    }
}
