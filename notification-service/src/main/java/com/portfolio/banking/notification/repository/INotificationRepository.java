package com.portfolio.banking.notification.repository;

import com.portfolio.banking.notification.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface INotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findAllByRecipientAccountIdOrderByCreatedAtDesc(UUID recipientAccountId);
}
