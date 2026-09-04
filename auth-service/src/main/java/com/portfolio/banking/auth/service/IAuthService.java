package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;

public interface IAuthService {

    /**
     * @throws com.portfolio.banking.auth.exception.EmailAlreadyExistsException if the email is already registered
     */
    UserResponse register(RegisterRequest request);

    /**
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException if the email is unknown or the password doesn't match
     */
    TokenResponse login(LoginRequest request);

    /**
     * Mints a token for a trusted internal caller (e.g. transaction-service),
     * carrying {@code role=SERVICE} rather than a user identity.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException if the client id is unknown or the secret doesn't match
     */
    TokenResponse issueServiceToken(ServiceTokenRequest request);
}
