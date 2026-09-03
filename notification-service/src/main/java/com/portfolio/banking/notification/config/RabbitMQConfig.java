package com.portfolio.banking.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This is a pure consumer of the {@code banking.events} topic exchange that
 * account-service and transaction-service already declare. Declaring it again
 * here, with matching properties (durable topic exchange, same name), is
 * exactly the pattern account-service's own RabbitMQConfig anticipates:
 * publishers don't know or care who's listening, and each consumer owns its
 * own queue and binding.
 * <p>
 * Bound with wildcard patterns ("account.*", "transfer.*") rather than exact
 * routing keys, so a future event under either prefix (e.g. "account.closed")
 * reaches this queue without a config change here - though the listener still
 * has to be taught to handle it; see {@code BankingEventListener}.
 */
@Configuration
public class RabbitMQConfig {

    @Value("${banking.rabbitmq.exchange}")
    private String exchangeName;

    @Value("${banking.rabbitmq.notification-queue}")
    private String queueName;

    @Value("${banking.rabbitmq.dead-letter-exchange}")
    private String deadLetterExchangeName;

    @Value("${banking.rabbitmq.dead-letter-queue}")
    private String deadLetterQueueName;

    @Bean
    public TopicExchange bankingEventsExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    /**
     * Where a message ends up after exhausting the retry attempts below. A
     * fanout, not a topic: whatever routing key the original message had is
     * irrelevant once it's been given up on - anything landing here just
     * needs to reach the one dead-letter queue for inspection.
     */
    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(deadLetterExchangeName, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueueName).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange());
    }

    /**
     * {@code x-dead-letter-exchange} is what makes a rejected, not-requeued
     * message land on the DLQ above instead of being silently discarded. It
     * only takes effect once the retry advice chain (see the container
     * factory below) gives up and rejects the message rather than asking for
     * another local retry.
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", deadLetterExchangeName)
                .build();
    }

    @Bean
    public Binding accountEventsBinding() {
        return BindingBuilder.bind(notificationQueue()).to(bankingEventsExchange()).with("account.*");
    }

    @Bean
    public Binding transferEventsBinding() {
        return BindingBuilder.bind(notificationQueue()).to(bankingEventsExchange()).with("transfer.*");
    }

    /**
     * Named {@code rabbitListenerContainerFactory} deliberately: that's the
     * bean name Spring Boot wires into every {@code @RabbitListener} by
     * default, so {@code BankingEventListener} doesn't need to reference it
     * explicitly.
     * <p>
     * The retry here guards against transient failures (a momentary DB
     * blip) - a handful of fast local retries of the same delivery, no
     * broker round-trip involved. Once those are exhausted,
     * {@link RejectAndDontRequeueRecoverer} rejects the message without
     * requeueing it, which - combined with the queue's
     * {@code x-dead-letter-exchange} argument - is what routes it to the DLQ
     * instead of endlessly redelivering a message this consumer can't
     * process (a malformed payload, an unrecoverable bug) or dropping it
     * silently.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // Belt and suspenders: even if the retry interceptor's own recoverer
        // weren't set below, this ensures a rejected message is never simply
        // requeued back onto this same queue to loop forever.
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(200L, 2.0, 2000L)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
