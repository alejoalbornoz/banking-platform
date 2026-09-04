package com.portfolio.banking.account;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.AmountRequest;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.repository.ILedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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
 * Real Postgres and a real optimistic-lock race, not a mocked repository -
 * exactly the thing {@code AccountServiceTest}'s own comments say a unit test
 * structurally cannot exercise. Run by failsafe under {@code mvn verify}, not
 * surefire's {@code mvn test}, since it needs Docker; see the root pom for why
 * that split exists.
 * <p>
 * Both a Postgres and a RabbitMQ container are required for the Spring
 * context to even start: {@code RabbitMQConfig} declares a {@code
 * TopicExchange} bean, and Spring AMQP's auto-configured {@code RabbitAdmin}
 * tries to declare it against a live broker as soon as the context refreshes.
 * <p>
 * Every request here carries a real, signed JWT - {@code AccountController}
 * dereferences {@code @AuthenticationPrincipal Jwt} to decide the account's
 * owner and to authorize credit/debit, so there's no meaningful way to test
 * the concurrency behavior with security simply switched off. Rather than
 * depend on a live auth-service (a whole extra Spring context this test
 * doesn't otherwise need), {@link TestSecurityConfig} swaps in a
 * self-contained test keypair: {@code JwtDecoder} validates against it, and
 * {@link #mintToken} signs with it, so tokens are genuinely verified
 * end-to-end without any network call to auth-service at all.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AccountConcurrencyIT.TestSecurityConfig.class)
class AccountConcurrencyIT {

    private static final KeyPair TEST_KEY_PAIR = generateTestKeyPair();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ILedgerEntryRepository ledgerEntryRepository;

    /**
     * Fires the same credit request, with the same Idempotency-Key, from N
     * threads released at the same instant. If idempotency were enforced by
     * a Java-level "look it up, then insert if absent" check instead of a
     * database unique constraint, this is exactly the scenario that would
     * slip through: two threads could both look, both find nothing, and both
     * insert. It has to be the constraint that decides, and this test proves
     * it does - the account ends up credited exactly once, no matter how
     * many requests raced for it.
     */
    @Test
    void concurrentCreditsWithSameIdempotencyKey_applyExactlyOnce() throws Exception {
        TestAccount account = createAccount(BigDecimal.ZERO);
        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        int threadCount = 20;

        fireConcurrently(threadCount,
                () -> creditWithClientRetry(account.id(), idempotencyKey, new BigDecimal("10.00")));

        long postingsForThisKey = ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(account.id()).stream()
                .filter(entry -> entry.getOperationKey().equals(idempotencyKey))
                .count();
        assertThat(postingsForThisKey).as("exactly one posting despite %d concurrent requests", threadCount)
                .isEqualTo(1);

        AccountResponse updated = getAsOwner("/api/v1/accounts/" + account.id(), account.ownerToken(), AccountResponse.class);
        assertThat(updated.balance()).as("credited once, not %d times", threadCount)
                .isEqualByComparingTo("10.00");

        LedgerResponse ledger = getAsOwner(
                "/api/v1/accounts/" + account.id() + "/ledger", account.ownerToken(), LedgerResponse.class);
        assertThat(ledger.reconciled()).isTrue();
    }

    /**
     * The other side of the same coin: N threads each credit the same
     * account with their own, distinct key. These are genuinely N different
     * operations, so every one of them must be applied - this is the
     * optimistic-lock retry in {@code AccountService.applyIdempotently}
     * converging under real concurrent contention on {@code @Version},
     * something a mocked repository (which never actually races) cannot
     * exercise.
     */
    @Test
    void concurrentCreditsWithDifferentKeys_allApplyUnderRealContention() throws Exception {
        TestAccount account = createAccount(BigDecimal.ZERO);
        int threadCount = 20;
        BigDecimal amountEach = new BigDecimal("10.00");

        fireConcurrently(threadCount,
                () -> creditWithClientRetry(account.id(), "distinct-key-" + UUID.randomUUID(), amountEach));

        AccountResponse updated = getAsOwner("/api/v1/accounts/" + account.id(), account.ownerToken(), AccountResponse.class);
        BigDecimal expectedTotal = amountEach.multiply(BigDecimal.valueOf(threadCount));
        assertThat(updated.balance()).as("all %d distinct credits applied, none lost to a lost update", threadCount)
                .isEqualByComparingTo(expectedTotal);

        assertThat(ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(account.id()))
                .hasSize(threadCount);
    }

    private record TestAccount(UUID id, String ownerToken) {
    }

    private TestAccount createAccount(BigDecimal openingBalance) {
        String ownerToken = mintToken(UUID.randomUUID().toString(), "USER");

        CreateAccountRequest request = new CreateAccountRequest(openingBalance, "USD");
        HttpHeaders headers = authHeaders(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/api/v1/accounts", new HttpEntity<>(request, headers), AccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new TestAccount(response.getBody().id(), ownerToken);
    }

    /**
     * account-service's own contract for {@code ConcurrentUpdateException}
     * (see its javadoc) is "please retry the request at the request level"
     * once its internal 3-attempt budget is exhausted - the server does not
     * promise to absorb arbitrarily many simultaneous writers to the same row
     * in a single call. With every thread in this test released at the exact
     * same instant, a 409 here and there is that documented contract doing
     * its job, not a bug; a real client is expected to retry, so this test
     * does too, rather than asserting every single HTTP call returns 200 and
     * risking a flaky test over something the system never promised.
     * <p>
     * Uses a SERVICE-role token, not the account owner's: {@code /credit} is
     * restricted to {@code ROLE_SERVICE} now (see account-service's
     * SecurityConfig) since a real end user is never meant to call it
     * directly - only transaction-service, orchestrating a transfer, does.
     */
    private ResponseEntity<AccountResponse> creditWithClientRetry(UUID accountId, String idempotencyKey, BigDecimal amount)
            throws InterruptedException {
        HttpHeaders headers = authHeaders(mintToken("test-transaction-service", "SERVICE"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        HttpEntity<AmountRequest> entity = new HttpEntity<>(new AmountRequest(amount), headers);

        ResponseEntity<AccountResponse> response = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            response = restTemplate.postForEntity(
                    "/api/v1/accounts/" + accountId + "/credit", entity, AccountResponse.class);
            if (response.getStatusCode() != HttpStatus.CONFLICT) {
                return response;
            }
            // A short gap between client-level retries, on top of the
            // server's own exponential backoff between its internal
            // attempts, gives the herd of other threads time to thin out
            // instead of every client hammering the row again in lockstep.
            Thread.sleep(50);
        }
        return response;
    }

    private <T> T getAsOwner(String path, String token, Class<T> responseType) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
        return restTemplate.exchange(path, HttpMethod.GET, entity, responseType).getBody();
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /**
     * Releases every task at the same instant via a shared latch, rather than
     * just submitting them to a pool - submission order alone doesn't
     * guarantee they actually overlap in time, and overlap is the entire
     * point of a concurrency test.
     */
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

    private static String mintToken(String subject, String role) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) TEST_KEY_PAIR.getPublic())
                .privateKey((RSAPrivateKey) TEST_KEY_PAIR.getPrivate())
                .keyID("test-key")
                .build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(subject)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static KeyPair generateTestKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TestSecurityConfig {

        /**
         * Overrides the auto-configured JwtDecoder (normally built from
         * {@code jwk-set-uri}, i.e. a live auth-service) with one that
         * validates against this test's own in-memory keypair instead - this
         * test's tokens are self-signed, never issued by a real auth-service.
         */
        @Bean
        public JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) TEST_KEY_PAIR.getPublic()).build();
        }
    }
}
