package com.portfolio.banking.notification.mapper;

import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.model.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationMapper implements INotificationMapper {

    @Override
    public NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getRecipientAccountId(),
                notification.getType().name(),
                notification.getMessage(),
                notification.getCreatedAt()
        );
    }
}
