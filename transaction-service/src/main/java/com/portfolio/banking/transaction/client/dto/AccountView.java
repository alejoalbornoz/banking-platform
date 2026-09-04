package com.portfolio.banking.transaction.client.dto;

import java.util.UUID;

/**
 * Just the fields transaction-service actually needs from account-service's
 * {@code GET /accounts/{id}} response. The real response carries balance,
 * status, and more - deserializing into this narrower shape simply ignores
 * whatever else is in the JSON.
 */
public record AccountView(UUID id, UUID ownerId) {
}
