package com.portfolio.banking.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "email is required")
        // RFC 5321's maximum, and the width of login_attempts.email - which
        // this becomes the primary key of, registered or not, so an
        // unbounded string here would be an unbounded insert there.
        @Size(max = 254, message = "email must be at most 254 characters")
        String email,

        @NotBlank(message = "password is required")
        String password
) {
}
