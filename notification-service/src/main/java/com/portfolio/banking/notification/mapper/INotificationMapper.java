package com.portfolio.banking.notification.mapper;

import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.model.Notification;

public interface INotificationMapper {

    NotificationResponse toResponse(Notification notification);
}
