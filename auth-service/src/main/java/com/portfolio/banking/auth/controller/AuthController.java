package com.portfolio.banking.auth.controller;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.ChangePasswordRequest;
import com.portfolio.banking.auth.dto.ForgotPasswordRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.ResetPasswordRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.service.IAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final IAuthService authService;

    public AuthController(IAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Trades a refresh token for a new pair. The token presented is consumed
     * in the process, so the caller must replace its stored copy with the one
     * that comes back - presenting the old one again is what reuse detection
     * reads as theft.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    /**
     * 204 whether or not the token was recognised. There is nothing to
     * return, and reporting "unknown token" would turn this into an oracle
     * for guessing valid ones.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

    /**
     * The only endpoint in this service that requires a token - which is why
     * auth-service validates its own JWTs at all (see {@code SecurityConfig}).
     * Everything else here IS the way in and cannot demand one.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt caller,
                                @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(UUID.fromString(caller.getSubject()), request);
    }

    /**
     * 202 whether or not the address is registered, and after exactly the
     * same work either way. Anything that distinguishes the two - a different
     * status, a different body, a noticeably different latency - is a way to
     * read a user list out of a service that never returns one.
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
    }

    /** Spends the token from the email and sets the new password. */
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }

    /** Used only by trusted internal callers (e.g. transaction-service), not end users. */
    @PostMapping("/service-token")
    public TokenResponse serviceToken(@Valid @RequestBody ServiceTokenRequest request) {
        return authService.issueServiceToken(request);
    }
}
