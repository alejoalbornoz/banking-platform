package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.config.ServiceClientsProperties;
import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.exception.EmailAlreadyExistsException;
import com.portfolio.banking.auth.exception.InvalidCredentialsException;
import com.portfolio.banking.auth.model.User;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_TOKEN_TTL_SECONDS = 3600L;
    private static final long SERVICE_TOKEN_TTL_SECONDS = 43200L;
    private static final String SERVICE_CLIENT_ID = "transaction-service";
    private static final String SERVICE_CLIENT_SECRET = "the-real-secret";

    @Mock
    private IUserRepository userRepository;

    @Mock
    private JwtEncoder jwtEncoder;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        ServiceClientsProperties serviceClientsProperties = new ServiceClientsProperties();
        serviceClientsProperties.setServiceClients(Map.of(SERVICE_CLIENT_ID, SERVICE_CLIENT_SECRET));

        authService = new AuthService(
                userRepository, passwordEncoder, jwtEncoder, serviceClientsProperties,
                USER_TOKEN_TTL_SECONDS, SERVICE_TOKEN_TTL_SECONDS);
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
