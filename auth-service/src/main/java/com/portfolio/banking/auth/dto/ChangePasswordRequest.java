package com.portfolio.banking.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The current password is required even though the caller already proved who
 * they are with a token. An access token lives in a browser and survives a
 * borrowed laptop; the password is the thing only the real owner knows, and
 * demanding it is what stops a stolen token from being upgraded into
 * permanent control of the account.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword is required")
        String currentPassword,

        @NotBlank(message = "newPassword is required")
        @Size(min = 8, message = "newPassword must be at least 8 characters")
        String newPassword
) {
}
