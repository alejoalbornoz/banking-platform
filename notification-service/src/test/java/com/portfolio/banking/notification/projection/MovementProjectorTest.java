package com.portfolio.banking.notification.projection;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.notification.model.AccountOwner;
import com.portfolio.banking.notification.model.Movement;
import com.portfolio.banking.notification.model.MovementKind;
import com.portfolio.banking.notification.model.ProcessedEvent;
import com.portfolio.banking.notification.repository.IAccountOwnerRepository;
import com.portfolio.banking.notification.repository.IMovementRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What each event turns into, and - the part that matters - what happens
 * when they arrive in the wrong order. Order is the one thing two
 * independent publishers on one exchange cannot promise, so a projection
 * that only works when it holds is a projection that will eventually be
 * quietly wrong.
 */
@ExtendWith(MockitoExtension.class)
class MovementProjectorTest {

    @Mock
    private IProcessedEventRepository processedEventRepository;

    @Mock
    private IAccountOwnerRepository accountOwnerRepository;

    @Mock
    private IMovementRepository movementRepository;

    private MovementProjector projector;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final UUID sourceOwner = UUID.randomUUID();
    private final UUID destinationOwner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        lenient().when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        projector = new MovementProjector(
                new ProjectionRunner(processedEventRepository, new TransactionTemplate(transactionManager)),
                accountOwnerRepository, movementRepository);
    }

    @Test
    void accountCreated_recordsTheOwnerAndAnOpeningMovement() {
        AccountCreatedEvent event = new AccountCreatedEvent(
                sourceId, "123456789012", sourceOwner, new BigDecimal("100.00"), "USD");

        projector.project(event);

        ArgumentCaptor<AccountOwner> owner = ArgumentCaptor.forClass(AccountOwner.class);
        verify(accountOwnerRepository).save(owner.capture());
        assertThat(owner.getValue().getOwnerId()).isEqualTo(sourceOwner);

        ArgumentCaptor<Movement> movement = ArgumentCaptor.forClass(Movement.class);
        verify(movementRepository).save(movement.capture());
        assertThat(movement.getValue().getKind()).isEqualTo(MovementKind.OPENING);
        assertThat(movement.getValue().getAmount()).isEqualByComparingTo("100.00");
        assertThat(movement.getValue().getOwnerId()).as("owner is in the same event").isEqualTo(sourceOwner);
        assertThat(movement.getValue().getCounterpartyAccountId()).as("an opening has no other side").isNull();
    }

    @Test
    void accountCreated_withZeroBalance_recordsNoMovement() {
        // Same rule as the ledger: an empty statement already sums to zero.
        projector.project(new AccountCreatedEvent(sourceId, "123456789012", sourceOwner, BigDecimal.ZERO, "USD"));

        verify(accountOwnerRepository).save(any());
        verify(movementRepository, never()).save(any());
    }

    @Test
    void transferCompleted_writesSentOnTheSourceAndReceivedOnTheDestination() {
        when(accountOwnerRepository.findById(sourceId))
                .thenReturn(Optional.of(new AccountOwner(sourceId, sourceOwner, "USD")));
        when(accountOwnerRepository.findById(destinationId))
                .thenReturn(Optional.of(new AccountOwner(destinationId, destinationOwner, "USD")));
        TransferCompletedEvent event = new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("25.00"), "USD");

        projector.project(event);

        List<Movement> written = capturedMovements();
        assertThat(written).hasSize(2);
        assertThat(written).anySatisfy(m -> {
            assertThat(m.getKind()).isEqualTo(MovementKind.SENT);
            assertThat(m.getAccountId()).isEqualTo(sourceId);
            assertThat(m.getOwnerId()).isEqualTo(sourceOwner);
            assertThat(m.getCounterpartyAccountId()).isEqualTo(destinationId);
            assertThat(m.getTransactionId()).isEqualTo(event.getTransactionId());
        });
        assertThat(written).anySatisfy(m -> {
            assertThat(m.getKind()).isEqualTo(MovementKind.RECEIVED);
            assertThat(m.getAccountId()).isEqualTo(destinationId);
            assertThat(m.getOwnerId()).isEqualTo(destinationOwner);
            assertThat(m.getCounterpartyAccountId()).isEqualTo(sourceId);
        });
    }

    /**
     * The out-of-order case, first half. A transfer arrives before this
     * service has heard of the destination account. The movement must still
     * be written - dropping it loses a statement line, rejecting it
     * dead-letters a message over a condition that resolves itself - just
     * without an owner yet.
     */
    @Test
    void transferCompleted_beforeTheAccountIsKnown_isWrittenWithoutAnOwnerRatherThanDropped() {
        when(accountOwnerRepository.findById(sourceId))
                .thenReturn(Optional.of(new AccountOwner(sourceId, sourceOwner, "USD")));
        when(accountOwnerRepository.findById(destinationId)).thenReturn(Optional.empty());

        projector.project(new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("25.00"), "USD"));

        List<Movement> written = capturedMovements();
        assertThat(written).hasSize(2);
        assertThat(written).filteredOn(m -> m.getKind() == MovementKind.RECEIVED)
                .singleElement()
                .satisfies(m -> assertThat(m.getOwnerId()).as("not yet anyone's").isNull());
        assertThat(written).filteredOn(m -> m.getKind() == MovementKind.SENT)
                .singleElement()
                .satisfies(m -> assertThat(m.getOwnerId()).isEqualTo(sourceOwner));
    }

    /**
     * Second half: when the creation finally arrives, everything that was
     * waiting for it is claimed. Either order produces the same final state,
     * which is the property a projection actually needs.
     */
    @Test
    void accountCreated_arrivingLate_claimsTheMovementsThatWereWaitingForIt() {
        when(movementRepository.claimUnowned(destinationId, destinationOwner)).thenReturn(3);

        projector.project(new AccountCreatedEvent(
                destinationId, "210987654321", destinationOwner, BigDecimal.ZERO, "USD"));

        verify(movementRepository).claimUnowned(destinationId, destinationOwner);
    }

    /**
     * Its own marker under its own name, so a redelivery skips this
     * projection independently of whether notifications already handled the
     * same event.
     */
    @Test
    void aRedeliveredEvent_isSkippedByThisProjectionOnItsOwnMarker() {
        when(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        projector.project(new TransferCompletedEvent(
                UUID.randomUUID(), sourceId, destinationId, new BigDecimal("25.00"), "USD"));

        verify(movementRepository, never()).saveAll(any());

        ArgumentCaptor<ProcessedEvent> marker = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).saveAndFlush(marker.capture());
        assertThat(marker.getValue().getProjection()).isEqualTo("movements");
    }

    @SuppressWarnings("unchecked")
    private List<Movement> capturedMovements() {
        ArgumentCaptor<List<Movement>> captor = ArgumentCaptor.forClass(List.class);
        verify(movementRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
