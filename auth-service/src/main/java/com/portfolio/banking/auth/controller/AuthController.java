package com.portfolio.banking.auth.controller;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.service.IAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    /** Used only by trusted internal callers (e.g. transaction-service), not end users. */
    @PostMapping("/service-token")
    public TokenResponse serviceToken(@Valid @RequestBody ServiceTokenRequest request) {
        return authService.issueServiceToken(request);
    }
}
