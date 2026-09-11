package com.portfolio.banking.notification.service;

import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.mapper.IMovementMapper;
import com.portfolio.banking.notification.model.Movement;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.repository.IMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MovementQueryService implements IMovementQueryService {

    private final IMovementRepository movementRepository;
    private final IMovementMapper movementMapper;

    public MovementQueryService(IMovementRepository movementRepository, IMovementMapper movementMapper) {
        this.movementRepository = movementRepository;
        this.movementMapper = movementMapper;
    }

    /**
     * {@code @Transactional} is fine here, unlike {@code listForAccount} on
     * notifications: this read makes no network call. That difference is the
     * entire payoff of the projection.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<MovementResponse> listForOwner(String callerId, UUID accountId, KeysetPage page) {
        UUID ownerId = UUID.fromString(callerId);

        List<Movement> fetched = page.isFirstPage()
                ? movementRepository.findFirstPageByOwner(ownerId, accountId, page.limitOnly())
                : movementRepository.findPageByOwnerAfter(
                        ownerId, accountId, page.afterCreatedAt(), page.afterId(), page.limitOnly());

        return page.build(fetched, movementMapper::toResponse, Movement::getOccurredAt, Movement::getId);
    }
}
