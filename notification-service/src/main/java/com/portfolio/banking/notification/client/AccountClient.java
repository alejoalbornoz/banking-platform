package com.portfolio.banking.notification.client;

import com.portfolio.banking.notification.client.dto.AccountView;
import com.portfolio.banking.notification.client.dto.RemoteErrorResponse;
import com.portfolio.banking.notification.exception.RemoteAccountException;
import com.portfolio.banking.notification.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class AccountClient implements IAccountClient {

    private final RestClient restClient;

    public AccountClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
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

    private RuntimeException mapRemoteError(UUID accountId, RestClientResponseException ex) {
        RemoteErrorResponse body = tryParseBody(ex);
        String errorCode = body != null ? body.errorCode() : null;
        String message = body != null ? body.message() : ex.getMessage();

        if (ex.getStatusCode().value() == 404 || "RESOURCE_NOT_FOUND".equals(errorCode)) {
            return ResourceNotFoundException.forEntity("Account", accountId);
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
