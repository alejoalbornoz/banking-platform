package com.portfolio.banking.notification.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RemoteErrorResponse(String errorCode, String message) {
}
