package com.portfolio.banking.auth.controller;

import com.portfolio.banking.auth.config.JwtKeyConfig;
import com.portfolio.banking.auth.config.SecurityConfig;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.exception.EmailAlreadyExistsException;
import com.portfolio.banking.auth.exception.InvalidCredentialsException;
import com.portfolio.banking.auth.exception.TooManyLoginAttemptsException;
import com.portfolio.banking.auth.service.IAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth-service's HTTP layer, which is unusual in one way: nearly everything
 * must be reachable WITHOUT a token, because handing out tokens is what it
 * does - and exactly one route must demand one. Getting that split wrong in
 * either direction is a bug nothing else in the module can see, and the
 * matcher ordering in {@code SecurityConfig} makes it easy to get wrong in
 * the direction that locks out precisely the users who cannot sign in.
 * <p>
 * {@link JwtKeyConfig} is imported because {@code SecurityConfig} builds its
 * decoder from the in-memory keypair; the decoder itself is then replaced
 * with a mock so tokens can be minted as plain strings.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtKeyConfig.class})
class AuthControllerWebTest {

    private static final String USER_TOKEN = "a-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IAuthService authService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void tokenDecodesToPrincipal() {
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(Jwt.withTokenValue("irrelevant")
                .header("alg", "RS256").subject(userId.toString()).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
    }

    // --- the one split that matters -----------------------------------------

    @Test
    void changingThePassword_requiresAToken_andTakesTheUserFromIt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"a-new-password\"}"))
                .andExpect(status().isUnauthorized());
        verify(authService, never()).changePassword(any(), any());

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"a-new-password\"}"))
                .andExpect(status().isNoContent());
        verify(authService).changePassword(eq(userId), any());
    }

    /**
     * These are for people who cannot sign in, so they must not demand a
     * token. A {@code /api/v1/auth/password/**} matcher declared before the
     * {@code /password} one would have failed this, and nothing else would
     * have noticed.
     */
    @Test
    void forgotAndReset_doNotRequireAToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.com\"}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"whatever-token\",\"newPassword\":\"a-new-password\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void theEntryPoints_doNotRequireAToken() throws Exception {
        when(authService.register(any())).thenReturn(new UserResponse(userId, "new@example.com", Instant.now()));
        when(authService.login(any())).thenReturn(new TokenResponse("access", 900, "refresh", 2592000));
        when(authService.refresh(any())).thenReturn(new TokenResponse("access", 900, "refresh", 2592000));

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isNoContent());
    }

    // --- what each failure looks like on the wire ----------------------------

    @Test
    void aServiceToken_carriesNoRefreshTokenField_notEvenAsNull() throws Exception {
        when(authService.issueServiceToken(any())).thenReturn(new TokenResponse("service-access", 43200));

        mockMvc.perform(post("/api/v1/auth/service-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"transaction-service\",\"clientSecret\":\"s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("service-access"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    /**
     * A 429 without {@code Retry-After} tells a well-behaved client to back
     * off but not by how much, which is the behaviour the status code exists
     * to prevent.
     */
    @Test
    void aThrottledLogin_is429WithRetryAfter() throws Exception {
        when(authService.login(any())).thenThrow(new TooManyLoginAttemptsException(Duration.ofSeconds(8)));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"guess\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "8"))
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void badCredentials_are401_andADuplicateEmail_is409() throws Exception {
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());
        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException("taken@example.com"));

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_EXISTS"));
    }

    // --- validation -----------------------------------------------------------

    /**
     * The email length cap is not cosmetic: the submitted address becomes the
     * primary key of {@code login_attempts}, registered or not, so an
     * unbounded string here would be an unbounded insert there.
     */
    @Test
    void anOversizedEmail_isRejectedBeforeReachingTheService() throws Exception {
        String tooLong = "x".repeat(255) + "@example.com";

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + tooLong + "\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));

        verify(authService, never()).login(any());
    }

    @Test
    void aShortNewPassword_isRejected_onBothChangeAndReset() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"old-password\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        mockMvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"));

        verify(authService, never()).changePassword(any(), any());
        verify(authService, never()).resetPassword(any());
    }
}
