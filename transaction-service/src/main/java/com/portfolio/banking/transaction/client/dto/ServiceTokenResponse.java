package com.portfolio.banking.transaction.client.dto;

public record ServiceTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
