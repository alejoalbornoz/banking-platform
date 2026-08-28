package com.portfolio.banking.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Defines the retry policy for optimistic-locking conflicts.
 * <p>
 * We use {@link RetryTemplate} explicitly, invoked from inside the service
 * method, rather than the {@code @Retryable} annotation. The reason: each
 * retry attempt needs its own fresh database transaction, so it re-reads the
 * row with the latest {@code version}. With {@code @Retryable}, that requires
 * the retry advisor to run *outside* the transactional advisor on the same
 * proxied method - correct, but easy to get backwards silently since Spring
 * resolves advisor order from bean definition order unless you pin it with
 * {@code @Order}. Calling {@code RetryTemplate.execute(...)} around a
 * {@code TransactionTemplate.execute(...)} block makes the nesting explicit
 * and impossible to get wrong.
 */
@Configuration
public class RetryConfig {

    @Bean
    public RetryTemplate optimisticLockRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                3,
                Map.of(ObjectOptimisticLockingFailureException.class, true)
        );
        retryTemplate.setRetryPolicy(retryPolicy);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(25L);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(200L);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }

    /**
     * A fresh {@link TransactionTemplate} so each retry attempt in
     * {@code optimisticLockRetryTemplate} opens a brand new transaction
     * (and therefore re-reads the row) instead of reusing a rolled-back one.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
