package com.portfolio.banking.notification.mapper;

import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.model.Movement;
import org.springframework.stereotype.Component;

@Component
public class MovementMapper implements IMovementMapper {

    @Override
    public MovementResponse toResponse(Movement movement) {
        return new MovementResponse(
                movement.getId(),
                movement.getAccountId(),
                movement.getKind().name(),
                movement.getAmount(),
                movement.getCurrency(),
                movement.getCounterpartyAccountId(),
                movement.getTransactionId(),
                movement.getOccurredAt()
        );
    }
}
