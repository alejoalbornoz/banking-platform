package com.portfolio.banking.auth;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.repository.IRefreshTokenRepository;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the parts of auth-service that {@code AuthServiceTest} structurally
 * cannot, because they only exist outside the service class:
 * <ul>
 *   <li><b>The signing path.</b> The unit test mocks {@code JwtEncoder}, so
 *       nothing there proves a real RS256 token is produced, or that the key
 *       published at {@code /.well-known/jwks.json} is the one that signed
 *       it. That loop is the entire basis on which the other three services
 *       trust this one, and it's verified here the same way they verify it:
 *       by building a decoder from the published JWK set.</li>
 *   <li><b>The unique email constraint.</b> The unit test mocks the
 *       repository, so it can only assert how a violation is handled, never
 *       that the database raises one.</li>
 *   <li><b>Configuration binding.</b> The unit test constructs
 *       {@code ServiceClientsProperties} by hand; only a real context proves
 *       the credentials in application.yml actually bind.</li>
 *   <li><b>Refresh token family revocation.</b> The unit test can assert
 *       that {@code revokeFamily} was called; whether that UPDATE really
 *       reaches every descendant of a login is a claim about SQL, and only a
 *       database settles it.</li>
 * </ul>
 * No RabbitMQ container here - unlike the other three services, this one
 * publishes and consumes nothing.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IUserRepository userRepository;

    @Autowired
    private IRefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Swaps out the request factory {@code TestRestTemplate} defaults to,
     * which wraps the JDK's {@code HttpURLConnection}. That connection treats
     * a 401 as an authentication challenge and tries to replay the request to
     * answer it - and a POST body it has already written can't be replayed,
     * so reading the response throws {@code HttpRetryException} and the 401
     * never reaches the assertion. This is the only IT in the project that
     * trips over it, because it's the only one asserting 401s; 403 and 409
     * don't put that connection into its auth-handling path.
     * <p>
     * Buffering the request body is the usual suggestion and doesn't help
     * here - the exception comes from reading the response, not writing the
     * request. Going through {@code java.net.http.HttpClient} instead avoids
     * the behaviour altogether, and needs no extra dependency.
     */
    @BeforeEach
    void useAClientThatDoesntSwallow401s() {
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void register_persistsTheUserWithAHashedPassword() {
        String email = uniqueEmail();

        ResponseEntity<UserResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterRequest(email, "password123"), UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().createdAt()).isNotNull();

        var stored = userRepository.findByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", stored.getPasswordHash())).isTrue();
    }

    /**
     * The token this returns is the only thing account-service,
     * transaction-service, and notification-service ever see of a user, and
     * they accept it purely on the strength of a signature checked against
     * the JWK set below. Decoding it here the same way proves the two halves
     * actually correspond - a test that only asserted "a token came back"
     * would pass just as happily on a token nothing could verify.
     */
    @Test
    void login_returnsAnRs256TokenThatVerifiesAgainstThePublishedJwks() {
        String email = uniqueEmail();
        UUID userId = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterRequest(email, "password123"), UserResponse.class)
                .getBody().id();

        ResponseEntity<TokenResponse> login = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "password123"), TokenResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().tokenType()).isEqualTo("Bearer");

        Jwt decoded = jwksBackedDecoder().decode(login.getBody().accessToken());

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("email")).isEqualTo(email);
        assertThat(decoded.getClaimAsString("role")).isEqualTo("USER");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("auth-service");
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now());
        assertThat(decoded.getHeaders().get("alg")).hasToString("RS256");
    }

    @Test
    void serviceToken_bindsCredentialsFromConfigurationAndCarriesTheServiceRole() {
        // transaction-service's client id/secret as registered in
        // auth-service's own application.yml.
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity("/api/v1/auth/service-token",
                new ServiceTokenRequest("transaction-service", "change-me-transaction-service-secret"),
                TokenResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Jwt decoded = jwksBackedDecoder().decode(response.getBody().accessToken());
        assertThat(decoded.getSubject()).isEqualTo("transaction-service");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(decoded.getClaimAsString("email")).isNull();
    }

    @Test
    void serviceToken_wrongSecret_isRejected() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/service-token",
                new ServiceTokenRequest("transaction-service", "not-the-secret"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
    }

    @Test
    void login_wrongPassword_isRejected() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, "wrong-password"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * {@code register} looks the address up before inserting, which handles
     * the ordinary duplicate but cannot handle simultaneous ones - both see
     * "free" and both insert. Only the unique constraint can arbitrate that,
     * and only a real database has one.
     * <p>
     * The assertions hold whichever mechanism won: exactly one row either
     * way, and a 409 for everyone else rather than the 500 an uncaught
     * constraint violation would produce. Whether the race is actually
     * exercised on a given run is up to the scheduler - what's deterministic
     * is that the outcome is correct either way.
     */
    @Test
    void register_sameEmailConcurrently_createsExactlyOneUser() throws Exception {
        String email = uniqueEmail();
        int attempts = 8;

        List<ResponseEntity<String>> responses = fireConcurrently(attempts, () ->
                restTemplate.postForEntity("/api/v1/auth/register",
                        new RegisterRequest(email, "password123"), String.class));

        assertThat(responses).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED)
                .as("exactly one registration succeeds")
                .hasSize(1);
        assertThat(responses).filteredOn(r -> r.getStatusCode() != HttpStatus.CREATED)
                .as("every loser is told the address is taken, not that the server broke")
                .allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getBody()).contains("EMAIL_ALREADY_EXISTS");
                });
        assertThat(userRepository.findByEmail(email)).isPresent();
    }

    @Test
    void refresh_rotatesTheTokenAndTheNewAccessTokenStillVerifies() {
        Session session = registerAndLogIn();

        ResponseEntity<TokenResponse> refreshed = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshTokenRequest(session.refreshToken()), TokenResponse.class);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().refreshToken())
                .as("rotation: the caller must be handed a different token")
                .isNotEqualTo(session.refreshToken());

        Jwt decoded = jwksBackedDecoder().decode(refreshed.getBody().accessToken());
        assertThat(decoded.getSubject()).isEqualTo(session.userId().toString());
        assertThat(decoded.getClaimAsString("role")).isEqualTo("USER");
    }

    /**
     * The whole mechanism, end to end, and the reason this belongs in an IT
     * rather than in {@code AuthServiceTest}: the unit test can only verify
     * that {@code revokeFamily} was <em>called</em>. Whether that call
     * actually reaches every descendant of the login - including the
     * perfectly valid token handed out one call earlier - is a claim about a
     * SQL UPDATE, and only a database can settle it.
     * <p>
     * The scenario is a theft. The attacker replays a token the real client
     * already spent; both are then locked out, which is the intended
     * outcome. Nothing here can tell victim from thief, so it distrusts both.
     */
    @Test
    void refresh_reusingASpentToken_killsEveryTokenInTheFamily() {
        Session session = registerAndLogIn();

        String secondToken = restTemplate.postForEntity("/api/v1/auth/refresh",
                        new RefreshTokenRequest(session.refreshToken()), TokenResponse.class)
                .getBody().refreshToken();

        // The stolen copy of the first token, replayed after the real client
        // already exchanged it.
        ResponseEntity<String> replay = restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshTokenRequest(session.refreshToken()), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // And the currently-valid token dies with it. Without the family, the
        // attacker would simply keep refreshing from whichever token they
        // hold.
        ResponseEntity<String> afterRevocation = restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshTokenRequest(secondToken), String.class);
        assertThat(afterRevocation.getStatusCode())
                .as("the successor is revoked too, not just the replayed token")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_endsTheSessionAndIsSafeToRepeat() {
        Session session = registerAndLogIn();

        assertThat(logout(session.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterLogout = restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshTokenRequest(session.refreshToken()), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Idempotent, and just as silent for a token it no longer honours as
        // for one it never issued.
        assertThat(logout(session.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout("a-token-that-was-never-issued").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Two sessions for one user must be independent - signing out of a laptop
     * cannot sign the phone out too. Each login starts its own family, and
     * this is what proves the revocation is scoped to one.
     */
    @Test
    void logout_endsOnlyTheSessionItWasGiven() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);
        String laptop = logIn(email).refreshToken();
        String phone = logIn(email).refreshToken();

        logout(laptop);

        assertThat(restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshTokenRequest(phone), String.class).getStatusCode())
                .as("the other session is untouched")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The refresh token is a bearer credential with a far longer life than
     * the access token, so a dump of this table must not be a dump of live
     * sessions.
     */
    @Test
    void refreshTokens_areStoredOnlyAsHashes() {
        Session session = registerAndLogIn();

        assertThat(refreshTokenRepository.findAll())
                .isNotEmpty()
                .as("the token itself is never written down")
                .noneMatch(stored -> stored.getTokenHash().equals(session.refreshToken()));
    }

    private record Session(UUID userId, String refreshToken) {
    }

    private Session registerAndLogIn() {
        String email = uniqueEmail();
        UUID userId = restTemplate.postForEntity("/api/v1/auth/register",
                        new RegisterRequest(email, "password123"), UserResponse.class)
                .getBody().id();
        TokenResponse tokens = logIn(email);
        return new Session(userId, tokens.refreshToken());
    }

    private TokenResponse logIn(String email) {
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
    }

    private ResponseEntity<Void> logout(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/logout", new RefreshTokenRequest(refreshToken), Void.class);
    }

    /** Exactly how the other services validate a token: fetch the JWK set, verify against it. */
    private JwtDecoder jwksBackedDecoder() {
        return NimbusJwtDecoder
                .withJwkSetUri("http://localhost:" + port + "/.well-known/jwks.json")
                .build();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** Releases every task at once, so the calls genuinely overlap rather than merely being submitted together. */
    private <T> List<T> fireConcurrently(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
