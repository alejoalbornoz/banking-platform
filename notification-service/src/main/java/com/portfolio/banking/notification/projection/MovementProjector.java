package com.portfolio.banking.notification.projection;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import com.portfolio.banking.common.event.TransferCompletedEvent;
import com.portfolio.banking.notification.model.AccountOwner;
import com.portfolio.banking.notification.model.Movement;
import com.portfolio.banking.notification.repository.IAccountOwnerRepository;
import com.portfolio.banking.notification.repository.IMovementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds the movements read model from events, and copes with the one thing
 * an event stream fed by two independent publishers cannot promise: order.
 * <p>
 * <b>The problem.</b> {@code account.created} comes from account-service,
 * published after commit. {@code transfer.completed} comes from
 * transaction-service, via its outbox relay. A transfer needs its accounts to
 * exist, so in practice the creation is published first - but "in practice"
 * is not a guarantee, and a redelivery, a consumer restart or a slow relay
 * can put a transfer in front of the creation of one of its own accounts. A
 * projection that assumed order would then have a movement it cannot
 * attribute to anyone, and the naive responses are all bad: dropping it
 * loses a statement line, rejecting it sends the message to the dead-letter
 * queue for a condition that would have resolved itself in a second, and
 * blocking the consumer until the other publisher catches up stalls every
 * other event behind it.
 * <p>
 * <b>The answer</b> is to write the movement anyway, with {@code ownerId}
 * null, and to have the {@code account.created} handler <em>claim</em>
 * whatever was waiting for it. Either order produces the same final state,
 * which is the property a projection actually needs. The only cost is that
 * between the two events the movement exists but is not yet in anyone's
 * statement - and that window is exactly as long as the delivery gap it
 * papers over.
 * <p>
 * <b>What this also reveals.</b> account-service publishes after commit with
 * no outbox, which the README defends as fine for a welcome notification: a
 * crash between commit and publish loses an event nobody would miss. That
 * defence no longer holds. This projection depends on {@code account.created}
 * for ownership, so a lost one now means an account whose movements are never
 * attributed to anyone - permanently, since nothing will ever publish it
 * again. The read model has turned a tolerable gap in another service into an
 * intolerable one. See "Known gaps" in the README.
 */
@Component
public class MovementProjector implements IMovementProjector {

    private static final Logger log = LoggerFactory.getLogger(MovementProjector.class);

    /** Part of the stored idempotency key - a contract, not a label. */
    static final String PROJECTION = "movements";

    private final ProjectionRunner projectionRunner;
    private final IAccountOwnerRepository accountOwnerRepository;
    private final IMovementRepository movementRepository;

    public MovementProjector(ProjectionRunner projectionRunner,
                              IAccountOwnerRepository accountOwnerRepository,
                              IMovementRepository movementRepository) {
        this.projectionRunner = projectionRunner;
        this.accountOwnerRepository = accountOwnerRepository;
        this.movementRepository = movementRepository;
    }

    @Override
    public void project(AccountCreatedEvent event) {
        projectionRunner.runOnce(PROJECTION, event.getEventId(), () -> {
            accountOwnerRepository.save(new AccountOwner(event.getAccountId(), event.getOwnerId(), event.getCurrency()));

            int claimed = movementRepository.claimUnowned(event.getAccountId(), event.getOwnerId());
            if (claimed > 0) {
                log.info("account.created for {} arrived after {} of its movement(s); claimed them for owner {}",
                        event.getAccountId(), claimed, event.getOwnerId());
            }

            // Zero-balance accounts open with no movement, exactly as they open
            // with no ledger entry: an empty statement already sums to zero.
            if (event.getOpeningBalance().signum() > 0) {
                movementRepository.save(Movement.opening(event));
            }
        });
    }

    @Override
    public void project(TransferCompletedEvent event) {
        projectionRunner.runOnce(PROJECTION, event.getEventId(), () -> {
            UUID sourceOwner = ownerOf(event.getSourceAccountId());
            UUID destinationOwner = ownerOf(event.getDestinationAccountId());

            movementRepository.saveAll(List.of(
                    Movement.sent(event, sourceOwner),
                    Movement.received(event, destinationOwner)));

            if (sourceOwner == null || destinationOwner == null) {
                log.info("transfer.completed {} projected before account.created for {}; owner to be claimed later",
                        event.getTransactionId(), sourceOwner == null
                                ? event.getSourceAccountId() : event.getDestinationAccountId());
            }
        });
    }

    /** Null when the account's creation has not reached this service yet. */
    private UUID ownerOf(UUID accountId) {
        return accountOwnerRepository.findById(accountId).map(AccountOwner::getOwnerId).orElse(null);
    }
}
