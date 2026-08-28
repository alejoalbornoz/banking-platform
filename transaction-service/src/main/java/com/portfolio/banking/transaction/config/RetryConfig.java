package com.portfolio.banking.transaction.config;

import com.portfolio.banking.transaction.exception.RemoteConcurrencyException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Configuration
public class RetryConfig {

    /**
     * Used around calls to account-service's debit/credit endpoints. Only
     * retries {@link RemoteConcurrencyException} - every other exception
     * (insufficient funds, not found, generic remote error) is a permanent
     * failure for this attempt and should propagate immediately.
     */
    @Bean
    public RetryTemplate remoteCallRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                3, Map.of(RemoteConcurrencyException.class, true));
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(50L);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(400L);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }

    /**
     * Each short DB write in the saga (create PENDING row, mark DEBITED,
     * mark COMPLETED + outbox, mark FAILED + outbox) runs in its own
     * transaction via this template, deliberately kept separate from the
     * REST calls to account-service in between. Never hold a DB transaction
     * open across a network call to another service.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
