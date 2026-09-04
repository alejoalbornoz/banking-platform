package com.portfolio.banking.transaction;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.portfolio.banking.transaction.client.IAccountClient;
import com.portfolio.banking.transaction.client.dto.AccountView;
import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.exception.InsufficientFundsException;
import com.portfolio.banking.transaction.exception.ResourceNotFoundException;
import com.portfolio.banking.transaction.model.OutboxEvent;
import com.portfolio.banking.transaction.model.Transaction;
import com.portfolio.banking.transaction.repository.IOutboxEventRepository;
import com.portfolio.banking.transaction.repository.ITransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The saga against a real Postgres, with only the HTTP boundary to
 * account-service faked ({@code IAccountClient} is mocked, exactly like the
 * existing unit test) - real Flyway migrations, real JPA mappings, real
 * {@code @Version} semantics, and a real {@code OutboxRelay} publishing to a
 * real RabbitMQ.
 * <p>
 * That's not a narrower version of the unit test with extra ceremony. Two
 * bugs shipped in this exact class that a mocked repository could never have
 * caught, because both are about what a real database actually does:
 * {@code OutboxEvent.payload} being {@code @Lob} over a plain TEXT column
 * failed Hibernate's schema validation at startup, and {@code TransferService}
 * discarding {@code save()}'s return value left every multi-step saga path
 * mutating a stale, already-superseded {@code @Version} - Mockito's
 * {@code save()} stub just echoes its input, so it can never reproduce either
 * failure. Every test below exercises one of the saga's actual multi-save
 * paths for exactly that reason.
 * <p>
 * {@code /transfers} now requires a real, validated JWT, and {@code
 * TransferService} calls {@code accountClient.getAccount(sourceId)} to check
 * ownership before doing anything else - so every request here carries a
 * token, and {@code accountClient} (already mocked to fake account-service's
 * debit/credit) is also stubbed to say the token's own subject owns {@code
 * sourceId}. Tokens are signed with a self-contained test keypair (see
 * {@link TestSecurityConfig}), not a live auth-service - same reasoning as
 * {@code AccountConcurrencyIT}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TransferSagaIT.TestSecurityConfig.class)
class TransferSagaIT {

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
    private ITransactionRepository transactionRepository;

    @Autowired
    private IOutboxEventRepository outboxEventRepository;

    @MockBean
    private IAccountClient accountClient;

    private final UUID sourceId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("50.00");
    private final UUID ownerId = UUID.randomUUID();
    private String callerToken;

    @BeforeEach
    void setUpCaller() {
        callerToken = mintToken(ownerId.toString(), "USER");
        lenient().when(accountClient.getAccount(sourceId)).thenReturn(new AccountView(sourceId, ownerId));
    }

    /**
     * The exact regression case: debit succeeds (first local save, PENDING ->
     * DEBITED), then credit succeeds (second local save, DEBITED ->
     * COMPLETED, plus the outbox insert - all three in one transaction). That
     * second save is precisely where the stale-version bug threw
     * {@code ObjectOptimisticLockingFailureException} against a real
     * database, every single time, for every transfer that got this far.
     */
    @Test
    void happyPath_persistsThroughBothSagaStepsAndPublishesToOutbox() {
        TransferRequest request = new TransferRequest(sourceId, destinationId, amount, "USD");
        String idempotencyKey = "saga-happy-" + UUID.randomUUID();

        ResponseEntity<TransferResponse> response = postTransfer(idempotencyKey, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("COMPLETED");

        UUID transactionId = response.getBody().transactionId();
        Transaction persisted = transactionRepository.findById(transactionId).orElseThrow();
        assertThat(persisted.getStatus().name()).isEqualTo("COMPLETED");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(transactionId))
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isPublished()).isTrue();
            assertThat(events.get(0).getEventType()).isEqualTo("transfer.completed");
        });
    }

    /**
     * The saga's other multi-save path: debit succeeds (first save), credit
     * fails, compensation succeeds, then {@code markFailedWithOutbox} runs a
     * second save on the same, by-then-stale-if-unfixed entity. Same bug
     * class as the happy path, different branch of the saga.
     */
    @Test
    void creditFails_compensatesAndPersistsFailedStatus() {
        doThrow(new ResourceNotFoundException("destination account not found"))
                .when(accountClient).credit(eq(destinationId), any(), eq(amount));

        TransferRequest request = new TransferRequest(sourceId, destinationId, amount, "USD");
        String idempotencyKey = "saga-compensate-" + UUID.randomUUID();

        ResponseEntity<TransferResponse> response = postTransfer(idempotencyKey, request);

        assertThat(response.getBody().status()).isEqualTo("FAILED");
        assertThat(response.getBody().failureReason()).contains("compensated");
        // The compensation is a credit back to the SOURCE account - verifies
        // money was actually put back, not just that the row says "failed".
        verify(accountClient).credit(eq(sourceId), any(), eq(amount));

        UUID transactionId = response.getBody().transactionId();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<OutboxEvent> events = outboxEventRepository.findAll().stream()
                    .filter(e -> e.getAggregateId().equals(transactionId))
                    .toList();
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isPublished()).isTrue();
            assertThat(events.get(0).getEventType()).isEqualTo("transfer.failed");
        });
    }

    /**
     * Reproduces, directly, the exact stuck state this bug left behind live:
     * a transaction row at DEBITED whose debit genuinely already happened -
     * account-service is never asked to debit again, and the resumed credit
     * call reuses the same operation key the original attempt would have
     * used, so account-service's own idempotency is what stops a second
     * credit even on retry.
     */
    @Test
    void resumingFromDebited_skipsDebitAndCompletesWithoutRepeatingIt() {
        String idempotencyKey = "saga-resume-" + UUID.randomUUID();
        Transaction stuckAtDebited = new Transaction(idempotencyKey, sourceId, destinationId, amount, "USD");
        stuckAtDebited.markDebited();
        Transaction saved = transactionRepository.save(stuckAtDebited);

        TransferRequest request = new TransferRequest(sourceId, destinationId, amount, "USD");
        ResponseEntity<TransferResponse> response = postTransfer(idempotencyKey, request);

        assertThat(response.getBody().status()).isEqualTo("COMPLETED");
        assertThat(response.getBody().transactionId()).isEqualTo(saved.getId());
        verify(accountClient, never()).debit(any(), any(), any());
        verify(accountClient).credit(eq(destinationId), any(), eq(amount));
    }

    @Test
    void debitFails_neverCallsCreditAndSkipsTheOutboxEntirely() {
        doThrow(new InsufficientFundsException("not enough balance"))
                .when(accountClient).debit(eq(sourceId), any(), eq(amount));

        TransferRequest request = new TransferRequest(sourceId, destinationId, amount, "USD");
        ResponseEntity<TransferResponse> response = postTransfer("saga-insufficient-" + UUID.randomUUID(), request);

        assertThat(response.getBody().status()).isEqualTo("FAILED");
        verify(accountClient, never()).credit(any(), any(), any());

        UUID transactionId = response.getBody().transactionId();
        // Nothing external happened, so nothing gets announced - see
        // TransferService's own javadoc on why this case has no outbox event.
        assertThat(outboxEventRepository.findAll().stream()
                .noneMatch(e -> e.getAggregateId().equals(transactionId)))
                .isTrue();
    }

    private ResponseEntity<TransferResponse> postTransfer(String idempotencyKey, TransferRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setBearerAuth(callerToken);
        HttpEntity<TransferRequest> entity = new HttpEntity<>(request, headers);
        return restTemplate.postForEntity("/api/v1/transfers", entity, TransferResponse.class);
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

        /** Overrides the auto-configured JwtDecoder (normally built from jwk-set-uri, i.e. a live auth-service). */
        @Bean
        public JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) TEST_KEY_PAIR.getPublic()).build();
        }
    }
}
