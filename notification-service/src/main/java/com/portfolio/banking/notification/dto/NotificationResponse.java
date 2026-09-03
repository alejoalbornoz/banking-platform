package com.portfolio.banking.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID recipientAccountId,
        String type,
        String message,
        Instant createdAt
) {
}
