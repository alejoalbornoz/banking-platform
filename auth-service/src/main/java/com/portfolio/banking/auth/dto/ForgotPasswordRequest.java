package com.portfolio.banking.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
        @NotBlank(message = "email is required")
        @Size(max = 254, message = "email must be at most 254 characters")
        String email
) {
}
