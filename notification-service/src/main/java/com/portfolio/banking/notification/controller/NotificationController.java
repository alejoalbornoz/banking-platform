package com.portfolio.banking.notification.controller;

import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.service.INotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final INotificationService notificationService;

    public NotificationController(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> listForAccount(@RequestParam UUID accountId) {
        return notificationService.listForAccount(accountId);
    }
}
