package com.portfolio.banking.notification.mapper;

import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.model.Movement;

public interface IMovementMapper {

    MovementResponse toResponse(Movement movement);
}
