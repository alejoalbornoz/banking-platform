package com.portfolio.banking.auth;

import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.ChangePasswordRequest;
import com.portfolio.banking.auth.dto.ForgotPasswordRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.ResetPasswordRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.model.LoginAttempt;
import com.portfolio.banking.auth.notify.IPasswordResetNotifier;
import com.portfolio.banking.auth.repository.ILoginAttemptRepository;
import com.portfolio.banking.auth.repository.IRefreshTokenRepository;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
 *   <li><b>The login-attempt counter under concurrency.</b> It is advanced
 *       by an {@code ON CONFLICT} upsert precisely because a burst of
 *       simultaneous guesses must not all read the same count and write it
 *       back as one. Only a real database can show that it does.</li>
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
    private ILoginAttemptRepository loginAttemptRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * The one step of the reset flow that leaves this system. Mocked so
     * the token can be captured; that it really goes out over SMTP is a
     * property of the mail server, not of this service.
     */
    @MockBean
    private IPasswordResetNotifier passwordResetNotifier;

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

    @Test
    void login_repeatedFailures_eventuallyReturn429WithARetryAfterHeader() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);

        // The free attempts, which must all still be plain 401s - somebody
        // mistyping their own password a few times cannot meet this.
        for (int i = 0; i < FREE_ATTEMPTS; i++) {
            assertThat(failedLogin(email).getStatusCode())
                    .as("attempt %d is still free", i + 1)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failedLogin(email).getStatusCode())
                .as("the attempt that trips it is still answered as a bad password")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> throttled = failedLogin(email);
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getBody()).contains("TOO_MANY_LOGIN_ATTEMPTS");
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("a 429 without Retry-After tells a client to back off but not for how long")
                .isNotNull();

        // And the correct password does not get you past it either - the
        // whole point is that no password is checked at all while held off.
        ResponseEntity<String> withRightPassword = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), String.class);
        assertThat(withRightPassword.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The throttle must not become the user-enumeration oracle that the
     * identical 401 exists to avoid. An address nobody registered has to be
     * counted, and rejected, exactly like a real one.
     */
    @Test
    void login_anAddressThatWasNeverRegistered_isThrottledIdentically() {
        String neverRegistered = uniqueEmail();

        for (int i = 0; i <= FREE_ATTEMPTS; i++) {
            assertThat(failedLogin(neverRegistered).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(failedLogin(neverRegistered).getStatusCode())
                .as("same treatment as a registered address, so the difference reveals nothing")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * The reason the counter is advanced by an {@code ON CONFLICT} upsert
     * rather than by reading it and writing it back. A mocked repository can
     * never show this: every attempt in a burst reads the same count before
     * any of them writes, so a read-then-write implementation records twenty
     * simultaneous guesses as one - which is precisely the shape an attacker
     * would use.
     */
    @Test
    void login_concurrentFailures_areAllCounted() throws Exception {
        String email = uniqueEmail();
        int attempts = 8;

        fireConcurrently(attempts, () -> failedLogin(email));

        assertThat(loginAttemptRepository.findById(email)).isPresent().get()
                .extracting(LoginAttempt::getFailedCount)
                .as("all %d attempts counted, not just the ones that happened to be spaced out", attempts)
                .isEqualTo(attempts);
    }

    @Test
    void login_success_clearsTheCounterSoAnEarlierBadRunCostsNothingLater() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);

        failedLogin(email);
        failedLogin(email);
        assertThat(loginAttemptRepository.findById(email)).isPresent();

        ResponseEntity<TokenResponse> ok = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(loginAttemptRepository.findById(email))
                .as("remembering your password should not leave you serving out a delay")
                .isEmpty();
    }

    /**
     * The claim that makes a password change worth making, and the one a
     * mocked repository cannot settle: {@code revokeAllForUser} is a single
     * UPDATE, and whether it really reaches a session started on another
     * device - a different family entirely - is a fact about SQL.
     */
    @Test
    void changingThePassword_endsSessionsStartedOnOtherDevices() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);
        TokenResponse laptop = logIn(email);
        TokenResponse phone = logIn(email);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(laptop.accessToken());
        ResponseEntity<Void> changed = restTemplate.exchange("/api/v1/auth/password", HttpMethod.POST,
                new HttpEntity<>(new ChangePasswordRequest("password123", "a-new-password"), headers),
                Void.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refreshWith(phone.refreshToken()).getStatusCode())
                .as("the other device's session is gone too, not just the one that asked")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshWith(laptop.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "a-new-password"), TokenResponse.class).getStatusCode())
                .as("and the new password works")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void changingThePassword_requiresAToken() {
        assertThat(restTemplate.postForEntity("/api/v1/auth/password",
                new ChangePasswordRequest("whatever", "a-new-password"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theResetFlow_worksEndToEndAndTheTokenIsSingleUse() {
        String email = uniqueEmail();
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, "password123"), UserResponse.class);
        TokenResponse session = logIn(email);

        assertThat(restTemplate.postForEntity("/api/v1/auth/password/forgot",
                new ForgotPasswordRequest(email), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(passwordResetNotifier).sendPasswordReset(eq(email), token.capture());

        assertThat(restTemplate.postForEntity("/api/v1/auth/password/reset",
                new ResetPasswordRequest(token.getValue(), "recovered-password"), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Spent: the same token cannot be replayed to set it again.
        assertThat(restTemplate.postForEntity("/api/v1/auth/password/reset",
                new ResetPasswordRequest(token.getValue(), "attacker-password"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A reset ends sessions exactly like a change does - the likeliest
        // reason to reset is that somebody else got in.
        assertThat(refreshWith(session.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "recovered-password"), TokenResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The forgot endpoint must not become the user-enumeration oracle the
     * rest of this service is built to avoid: an unregistered address gets
     * the same 202 and no mail.
     */
    @Test
    void forgotPassword_forAnAddressNobodyRegistered_answersIdentically() {
        assertThat(restTemplate.postForEntity("/api/v1/auth/password/forgot",
                new ForgotPasswordRequest(uniqueEmail()), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        verify(passwordResetNotifier, never()).sendPasswordReset(any(), any());
    }

    private ResponseEntity<String> refreshWith(String refreshToken) {
        return restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshTokenRequest(refreshToken), String.class);
    }

    /** Matches {@code banking.security.login-throttle.free-attempts} in application.yml. */
    private static final int FREE_ATTEMPTS = 5;

    private ResponseEntity<String> failedLogin(String email) {
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "definitely-not-the-password"), String.class);
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
