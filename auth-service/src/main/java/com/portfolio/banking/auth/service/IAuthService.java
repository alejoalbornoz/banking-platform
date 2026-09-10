package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.ChangePasswordRequest;
import com.portfolio.banking.auth.dto.ForgotPasswordRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.ResetPasswordRequest;
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
     * Changes the authenticated caller's password and ends every session
     * they have, everywhere.
     * <p>
     * The current password is required on top of the access token: a
     * token is something a browser holds, the password is something only
     * the owner knows.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException
     *         if the current password doesn't match
     */
    void changePassword(java.util.UUID userId, ChangePasswordRequest request);

    /**
     * Emails a single-use reset token, if that address belongs to anyone.
     * <p>
     * Returns normally either way. Reporting "no such user" would answer
     * the question the whole rest of this service is built not to answer.
     */
    void requestPasswordReset(ForgotPasswordRequest request);

    /**
     * Spends a reset token and sets the new password, ending every
     * session for that user.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException
     *         if the token is unknown, expired, or already spent
     */
    void resetPassword(ResetPasswordRequest request);

    /**
     * Mints a token for a trusted internal caller (e.g. transaction-service),
     * carrying {@code role=SERVICE} rather than a user identity.
     *
     * @throws com.portfolio.banking.auth.exception.InvalidCredentialsException if the client id is unknown or the secret doesn't match
     */
    TokenResponse issueServiceToken(ServiceTokenRequest request);
}
