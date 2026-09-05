package com.portfolio.banking.notification.client.dto;

import java.util.UUID;

/**
 * Just the field notification-service actually needs from account-service's
 * {@code GET /accounts/{id}} response, to check who owns an account before
 * showing its notifications to whoever's asking.
 */
public record AccountView(UUID id, UUID ownerId) {
}
