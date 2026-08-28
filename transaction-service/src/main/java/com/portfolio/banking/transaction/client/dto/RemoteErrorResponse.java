package com.portfolio.banking.transaction.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteErrorResponse(String errorCode, String message) {
}
