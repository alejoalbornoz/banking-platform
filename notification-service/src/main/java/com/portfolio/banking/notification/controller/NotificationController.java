package com.portfolio.banking.notification.controller;

import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.service.INotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final INotificationService notificationService;

    public NotificationController(INotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public PageResponse<NotificationResponse> listForAccount(@AuthenticationPrincipal Jwt caller,
                                                               @RequestParam UUID accountId,
                                                               @RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer limit) {
        return notificationService.listForAccount(caller.getSubject(), accountId, KeysetPage.of(cursor, limit));
    }
}
