package com.portfolio.banking.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Carries the refresh token in the body rather than in a query string or a
 * header: query strings end up in access logs, proxy logs and browser
 * history, and this is a bearer credential with a far longer life than the
 * access token it buys.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {
}
