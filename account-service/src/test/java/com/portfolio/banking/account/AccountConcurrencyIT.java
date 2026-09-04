package com.portfolio.banking.account;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.AmountRequest;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.repository.ILedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
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
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountConcurrencyIT {

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
        UUID accountId = createAccount(BigDecimal.ZERO);
        String idempotencyKey = "concurrent-key-" + UUID.randomUUID();
        int threadCount = 20;

        fireConcurrently(threadCount,
                () -> creditWithClientRetry(accountId, idempotencyKey, new BigDecimal("10.00")));

        long postingsForThisKey = ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .filter(entry -> entry.getOperationKey().equals(idempotencyKey))
                .count();
        assertThat(postingsForThisKey).as("exactly one posting despite %d concurrent requests", threadCount)
                .isEqualTo(1);

        AccountResponse account = restTemplate.getForObject("/api/v1/accounts/" + accountId, AccountResponse.class);
        assertThat(account.balance()).as("credited once, not %d times", threadCount)
                .isEqualByComparingTo("10.00");

        LedgerResponse ledger = restTemplate.getForObject("/api/v1/accounts/" + accountId + "/ledger", LedgerResponse.class);
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
        UUID accountId = createAccount(BigDecimal.ZERO);
        int threadCount = 20;
        BigDecimal amountEach = new BigDecimal("10.00");

        fireConcurrently(threadCount,
                () -> creditWithClientRetry(accountId, "distinct-key-" + UUID.randomUUID(), amountEach));

        AccountResponse account = restTemplate.getForObject("/api/v1/accounts/" + accountId, AccountResponse.class);
        BigDecimal expectedTotal = amountEach.multiply(BigDecimal.valueOf(threadCount));
        assertThat(account.balance()).as("all %d distinct credits applied, none lost to a lost update", threadCount)
                .isEqualByComparingTo(expectedTotal);

        assertThat(ledgerEntryRepository.findAllByAccountIdOrderByCreatedAtDesc(accountId))
                .hasSize(threadCount);
    }

    private UUID createAccount(BigDecimal openingBalance) {
        CreateAccountRequest request = new CreateAccountRequest(UUID.randomUUID(), openingBalance, "USD");
        ResponseEntity<AccountResponse> response =
                restTemplate.postForEntity("/api/v1/accounts", request, AccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
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
     */
    private ResponseEntity<AccountResponse> creditWithClientRetry(UUID accountId, String idempotencyKey, BigDecimal amount)
            throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
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
}
