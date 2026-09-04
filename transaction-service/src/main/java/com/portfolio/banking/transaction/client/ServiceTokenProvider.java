package com.portfolio.banking.transaction.client;

import com.portfolio.banking.transaction.client.dto.ServiceTokenRequest;
import com.portfolio.banking.transaction.client.dto.ServiceTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Obtains and caches the token transaction-service uses to call
 * account-service's {@code /credit}, {@code /debit}, and
 * {@code /accounts/{id}} endpoints as a trusted internal caller, not as any
 * particular user. See "Authentication" in the README for why
 * account-service needs that distinction at all - crediting a transfer's
 * destination account can never pass an ownership check, since the
 * destination belongs to someone else by definition.
 */
@Component
public class ServiceTokenProvider {

    /** Refresh a little before expiry so a call never races the token dying mid-flight. */
    private static final long REFRESH_BEFORE_EXPIRY_SECONDS = 60;

    private final RestClient authServiceRestClient;
    private final String clientId;
    private final String clientSecret;

    private volatile CachedToken cachedToken;

    public ServiceTokenProvider(RestClient authServiceRestClient,
                                 @Value("${services.auth-service.client-id}") String clientId,
                                 @Value("${services.auth-service.client-secret}") String clientSecret) {
        this.authServiceRestClient = authServiceRestClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public synchronized String getToken() {
        if (cachedToken == null || cachedToken.isExpiringSoon()) {
            cachedToken = fetchNewToken();
        }
        return cachedToken.value();
    }

    private CachedToken fetchNewToken() {
        ServiceTokenResponse response = authServiceRestClient.post()
                .uri("/api/v1/auth/service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ServiceTokenRequest(clientId, clientSecret))
                .retrieve()
                .body(ServiceTokenResponse.class);
        return new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresInSeconds()));
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isExpiringSoon() {
            return Instant.now().isAfter(expiresAt.minusSeconds(REFRESH_BEFORE_EXPIRY_SECONDS));
        }
    }
}
