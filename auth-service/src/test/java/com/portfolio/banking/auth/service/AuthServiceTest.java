package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.config.ServiceClientsProperties;
import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.exception.EmailAlreadyExistsException;
import com.portfolio.banking.auth.exception.InvalidCredentialsException;
import com.portfolio.banking.auth.model.RefreshToken;
import com.portfolio.banking.auth.model.User;
import com.portfolio.banking.auth.repository.IRefreshTokenRepository;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_TOKEN_TTL_SECONDS = 900L;
    private static final long REFRESH_TOKEN_TTL_SECONDS = 2592000L;
    private static final long SERVICE_TOKEN_TTL_SECONDS = 43200L;
    private static final String SERVICE_CLIENT_ID = "transaction-service";
    private static final String SERVICE_CLIENT_SECRET = "the-real-secret";

    @Mock
    private IUserRepository userRepository;

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private IRefreshTokenRepository refreshTokenRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        ServiceClientsProperties serviceClientsProperties = new ServiceClientsProperties();
        serviceClientsProperties.setServiceClients(Map.of(SERVICE_CLIENT_ID, SERVICE_CLIENT_SECRET));

        // Real TransactionTemplate over a mocked manager: commit and
        // rollback are no-ops, but the callback still runs, so the
        // REQUIRES_NEW revocation path is exercised rather than stubbed out.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        authService = new AuthService(
                userRepository, refreshTokenRepository, passwordEncoder, jwtEncoder,
                serviceClientsProperties, transactionManager,
                USER_TOKEN_TTL_SECONDS, SERVICE_TOKEN_TTL_SECONDS, REFRESH_TOKEN_TTL_SECONDS);
    }

    @Test
    void register_newEmail_savesHashedPasswordAndReturnsUser() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = authService.register(new RegisterRequest("new@example.com", "password123"));

        assertThat(response.email()).isEqualTo("new@example.com");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void register_emailAlreadyTaken_throws() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("taken@example.com", "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void register_emailTakenConcurrently_reportsTheSameConflictRatherThanTheRawViolation() {
        // The lookup said the address was free, then a concurrent
        // registration of it committed first. The caller's situation is
        // identical to the ordinary duplicate, so the answer should be too -
        // not the 500 a raw constraint violation would become.
        when(userRepository.existsByEmail("racy@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("racy@example.com", "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void login_correctPassword_returnsTokenCarryingUserClaims() {
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId, "user@example.com", passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtEncoder.encode(any())).thenReturn(fakeJwt("signed-token"));

        TokenResponse response = authService.login(new LoginRequest("user@example.com", "correct-password"));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(USER_TOKEN_TTL_SECONDS);

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());
        var claims = captor.getValue().getClaims();
        assertThat(claims.getClaimAsString("sub")).isEqualTo(userId.toString());
        assertThat(claims.getClaimAsString("email")).isEqualTo("user@example.com");
        assertThat(claims.getClaimAsString("role")).isEqualTo("USER");
        assertThat(claims.getClaimAsString("iss")).isEqualTo("auth-service");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        User user = userWithId(UUID.randomUUID(), "user@example.com", passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void issueServiceToken_correctSecret_returnsTokenCarryingServiceRole() {
        when(jwtEncoder.encode(any())).thenReturn(fakeJwt("service-token"));

        TokenResponse response = authService.issueServiceToken(
                new ServiceTokenRequest(SERVICE_CLIENT_ID, SERVICE_CLIENT_SECRET));

        assertThat(response.accessToken()).isEqualTo("service-token");
        assertThat(response.expiresInSeconds()).isEqualTo(SERVICE_TOKEN_TTL_SECONDS);

        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());
        var claims = captor.getValue().getClaims();
        assertThat(claims.getClaimAsString("sub")).isEqualTo(SERVICE_CLIENT_ID);
        assertThat(claims.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(claims.getClaimAsString("email")).isNull();
    }

    @Test
    void issueServiceToken_wrongSecret_throwsInvalidCredentials() {
        assertThatThrownBy(() -> authService.issueServiceToken(
                new ServiceTokenRequest(SERVICE_CLIENT_ID, "wrong-secret")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void issueServiceToken_unknownClientId_throwsInvalidCredentials() {
        assertThatThrownBy(() -> authService.issueServiceToken(
                new ServiceTokenRequest("unknown-client", "anything")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_issuesARefreshTokenAndStoresOnlyItsHash() {
        givenUserCanLogIn();

        TokenResponse response = authService.login(new LoginRequest("user@example.com", "correct-password"));

        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.refreshExpiresInSeconds()).isEqualTo(REFRESH_TOKEN_TTL_SECONDS);

        RefreshToken saved = captureSavedToken();
        // The point of the whole column: a dump of this table must not hand
        // anyone a working credential.
        assertThat(saved.getTokenHash())
                .isNotEqualTo(response.refreshToken())
                .isEqualTo(sha256Hex(response.refreshToken()))
                .hasSize(64);
    }

    @Test
    void login_twice_startsTwoIndependentFamilies() {
        // Signing in on a second device must not put both sessions in one
        // family - revoking one would then silently sign the other out too.
        givenUserCanLogIn();

        authService.login(new LoginRequest("user@example.com", "correct-password"));
        authService.login(new LoginRequest("user@example.com", "correct-password"));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getFamilyId())
                .isNotEqualTo(captor.getAllValues().get(1).getFamilyId());
    }

    @Test
    void refresh_validToken_consumesItAndIssuesASuccessorInTheSameFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken stored = storedToken(userId, Instant.now().plusSeconds(3600));
        givenPresentedTokenResolvesTo(stored);
        when(refreshTokenRepository.consume(eq(stored.getId()), any())).thenReturn(1);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(userWithId(userId, "user@example.com", "irrelevant")));
        when(jwtEncoder.encode(any())).thenReturn(fakeJwt("new-access-token"));

        TokenResponse response = authService.refresh(new RefreshTokenRequest(RAW_TOKEN));

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        // Rotation: the caller gets a different token back, and the old one is
        // now spent.
        assertThat(response.refreshToken()).isNotEqualTo(RAW_TOKEN);
        verify(refreshTokenRepository).consume(eq(stored.getId()), any());

        RefreshToken successor = captureSavedToken();
        assertThat(successor.getFamilyId()).isEqualTo(stored.getFamilyId());
        assertThat(successor.getUserId()).isEqualTo(userId);
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    /**
     * The heart of it. {@code consume} returning zero means the row was
     * already spent (or revoked underneath us), which cannot happen to an
     * honest client - it replaced its token the first time. Two holders means
     * one thief, and since there is no way to tell which one is calling, the
     * only safe move is to end the whole family.
     */
    @Test
    void refresh_tokenAlreadyUsed_revokesTheEntireFamily() {
        RefreshToken stored = storedToken(UUID.randomUUID(), Instant.now().plusSeconds(3600));
        givenPresentedTokenResolvesTo(stored);
        when(refreshTokenRepository.consume(eq(stored.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(RAW_TOKEN)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository).revokeFamily(eq(stored.getFamilyId()), any());
        // No successor: the family is finished, not rotated.
        verify(refreshTokenRepository, never()).save(any());
    }

    /**
     * An expired token is the mechanism working, not evidence of anything, so
     * it must not take the family down with it - otherwise coming back after
     * a month away would look identical to a theft.
     */
    @Test
    void refresh_expiredToken_isRejectedWithoutRevokingTheFamily() {
        RefreshToken expired = storedToken(UUID.randomUUID(), Instant.now().minusSeconds(1));
        givenPresentedTokenResolvesTo(expired);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(RAW_TOKEN)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
        verify(refreshTokenRepository, never()).consume(any(), any());
    }

    @Test
    void refresh_revokedToken_isRejectedWithoutReconsumingIt() {
        RefreshToken revoked = storedToken(UUID.randomUUID(), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(revoked, "revokedAt", Instant.now());
        givenPresentedTokenResolvesTo(revoked);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(RAW_TOKEN)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).consume(any(), any());
    }

    @Test
    void refresh_unknownToken_throwsInvalidCredentials() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(RAW_TOKEN))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(RAW_TOKEN)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void logout_revokesTheFamilyTheTokenBelongsTo() {
        RefreshToken stored = storedToken(UUID.randomUUID(), Instant.now().plusSeconds(3600));
        givenPresentedTokenResolvesTo(stored);

        authService.logout(new RefreshTokenRequest(RAW_TOKEN));

        verify(refreshTokenRepository).revokeFamily(eq(stored.getFamilyId()), any());
    }

    /**
     * No exception for an unknown token: an endpoint that answered "I don't
     * know that one" would confirm, for anyone working through guesses, which
     * ones are real.
     */
    @Test
    void logout_unknownToken_isASilentNoOp() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(RAW_TOKEN))).thenReturn(Optional.empty());

        authService.logout(new RefreshTokenRequest(RAW_TOKEN));

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    /** Any value works - what matters is that the service hashes it before looking it up. */
    private static final String RAW_TOKEN = "a-refresh-token-as-the-client-holds-it";

    private void givenUserCanLogIn() {
        User user = userWithId(UUID.randomUUID(), "user@example.com", passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(jwtEncoder.encode(any())).thenReturn(fakeJwt("signed-token"));
    }

    private void givenPresentedTokenResolvesTo(RefreshToken stored) {
        when(refreshTokenRepository.findByTokenHash(sha256Hex(RAW_TOKEN))).thenReturn(Optional.of(stored));
    }

    private RefreshToken captureSavedToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    /** {@code id} is Hibernate-generated (no public setter); tests fake it in. */
    private static RefreshToken storedToken(UUID userId, Instant expiresAt) {
        RefreshToken token = new RefreshToken(sha256Hex(RAW_TOKEN), userId, UUID.randomUUID(), expiresAt);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

    /**
     * Computed here independently rather than by calling into the service, so
     * the test pins the stored format instead of agreeing with whatever the
     * production code happens to do.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code id} is Hibernate-generated (no public setter); tests fake it in. */
    private static User userWithId(UUID id, String email, String passwordHash) {
        User user = new User(email, passwordHash);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Jwt fakeJwt(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .claim("sub", "irrelevant-for-this-fake")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
