package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
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
     * Exchanges a refresh token for a new access/refresh pair, rotating the
     * one presented so it can never be used again.
     * <p>
     * A token presented twice is treated as evidence that two parties hold
     * it, and revokes its whole family - see the implementation for why that
     * is the only safe reading, and what it costs.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException
     *         if the token is unknown, expired, revoked, or already used
     */
    TokenResponse refresh(RefreshTokenRequest request);

    /**
     * Revokes the token family the given refresh token belongs to, ending
     * that session everywhere it was rotated to.
     * <p>
     * Idempotent, and silent about whether the token existed: reporting
     * "unknown token" would confirm, for anyone holding a guess, that other
     * guesses are real sessions.
     */
    void logout(RefreshTokenRequest request);

    /**
     * Mints a token for a trusted internal caller (e.g. transaction-service),
     * carrying {@code role=SERVICE} rather than a user identity.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException if the client id is unknown or the secret doesn't match
     */
    TokenResponse issueServiceToken(ServiceTokenRequest request);
}
