package com.portfolio.banking.transaction.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${banking.rabbitmq.exchange}")
    private String exchangeName;

    @Bean
    public TopicExchange bankingEventsExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        // No Jackson2JsonMessageConverter here on purpose: OutboxRelay sends
        // already-serialized JSON strings as raw bytes (see its comment), so
        // a converter would double-encode them.
        return new RabbitTemplate(connectionFactory);
    }
}
