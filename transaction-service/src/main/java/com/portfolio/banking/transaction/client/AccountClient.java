package com.portfolio.banking.transaction.client;

import com.portfolio.banking.transaction.client.dto.AccountView;
import com.portfolio.banking.transaction.client.dto.AmountClientRequest;
import com.portfolio.banking.transaction.client.dto.RemoteErrorResponse;
import com.portfolio.banking.transaction.exception.InsufficientFundsException;
import com.portfolio.banking.transaction.exception.RemoteAccountException;
import com.portfolio.banking.transaction.exception.RemoteConcurrencyException;
import com.portfolio.banking.transaction.exception.ResourceNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class AccountClient implements IAccountClient {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public AccountClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    @Override
    public void debit(UUID accountId, String operationKey, BigDecimal amount) {
        call(accountId, operationKey, amount, "debit");
    }

    @Override
    public void credit(UUID accountId, String operationKey, BigDecimal amount) {
        call(accountId, operationKey, amount, "credit");
    }

    @Override
    public AccountView getAccount(UUID accountId) {
        try {
            return restClient.get()
                    .uri("/api/v1/accounts/{id}", accountId)
                    .retrieve()
                    .body(AccountView.class);
        } catch (RestClientResponseException ex) {
            throw mapRemoteError(accountId, ex);
        }
    }

    private void call(UUID accountId, String operationKey, BigDecimal amount, String operation) {
        try {
            restClient.post()
                    .uri("/api/v1/accounts/{id}/{operation}", accountId, operation)
                    .contentType(MediaType.APPLICATION_JSON)
                    // The retry template around this call can fire after a
                    // timeout on a request that actually committed. This header
                    // is what makes that retry a no-op on account-service's side
                    // instead of a second withdrawal.
                    .header(IDEMPOTENCY_KEY_HEADER, operationKey)
                    .body(new AmountClientRequest(amount))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw mapRemoteError(accountId, ex);
        }
    }

    /**
     * Translates account-service's HTTP error responses into our own
     * exception hierarchy, using the {@code errorCode} field it publishes
     * (see account-service's ErrorResponse/GlobalExceptionHandler) rather
     * than just the HTTP status, since a 409 can mean either "insufficient
     * funds" (don't retry) or "concurrent update" (worth retrying) and only
     * the error code tells them apart.
     */
    private RuntimeException mapRemoteError(UUID accountId, RestClientResponseException ex) {
        RemoteErrorResponse body = tryParseBody(ex);
        String errorCode = body != null ? body.errorCode() : null;
        String message = body != null ? body.message() : ex.getMessage();

        if (ex.getStatusCode().value() == 404 || "RESOURCE_NOT_FOUND".equals(errorCode)) {
            return ResourceNotFoundException.forEntity("Account", accountId);
        }
        if ("INSUFFICIENT_FUNDS".equals(errorCode)) {
            return new InsufficientFundsException(message);
        }
        if ("CONCURRENT_UPDATE".equals(errorCode)) {
            return new RemoteConcurrencyException(message);
        }
        return new RemoteAccountException(
                "account-service call failed for account " + accountId + ": " + message);
    }

    private RemoteErrorResponse tryParseBody(RestClientResponseException ex) {
        try {
            return ex.getResponseBodyAs(RemoteErrorResponse.class);
        } catch (Exception parseFailure) {
            return null;
        }
    }
}
