package com.portfolio.banking.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param accessToken     the short-lived JWT every other service validates
 * @param refreshToken    exchanged at {@code POST /api/v1/auth/refresh} for a
 *                         new pair. Absent - not null in the JSON, absent -
 *                         for service tokens: a service holds its own
 *                         client-id/secret and can simply ask for another
 *                         token, so handing it a second credential to store
 *                         and rotate would add risk and buy nothing.
 * @param refreshExpiresInSeconds how long the refresh token itself lasts,
 *                         which is what really bounds a session
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        Long refreshExpiresInSeconds
) {
    /** A service token: no refresh token, by design. */
    public TokenResponse(String accessToken, long expiresInSeconds) {
        this(accessToken, "Bearer", expiresInSeconds, null, null);
    }

    /** A user token pair, from a login or a refresh. */
    public TokenResponse(String accessToken, long expiresInSeconds,
                          String refreshToken, long refreshExpiresInSeconds) {
        this(accessToken, "Bearer", expiresInSeconds, refreshToken, refreshExpiresInSeconds);
    }
}
