package com.portfolio.banking.notification.client;

import com.portfolio.banking.notification.client.dto.AccountView;

import java.util.UUID;

/**
 * Used only to check who owns an account before showing its notifications to
 * whoever's asking - never as a user-facing operation in its own right.
 */
public interface IAccountClient {

    /** @throws com.portfolio.banking.notification.exception.ResourceNotFoundException account doesn't exist */
    AccountView getAccount(UUID accountId);
}
