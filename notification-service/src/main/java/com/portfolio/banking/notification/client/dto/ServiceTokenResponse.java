package com.portfolio.banking.notification.client.dto;

public record ServiceTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
