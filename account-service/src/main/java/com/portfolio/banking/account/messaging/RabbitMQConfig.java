package com.portfolio.banking.account.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * We use a topic exchange (not a direct queue) because account-service only
 * knows that "an account was created" - it has no business knowing who's
 * listening. Notification-service, and later an audit service, will each
 * declare their own queue and bind it to this exchange with whatever
 * routing key pattern they care about (e.g. "account.*"). That binding lives
 * in the consumer, not here, so publishers and consumers can be deployed
 * independently.
 */
@Configuration
public class RabbitMQConfig {

    @Value("${banking.rabbitmq.exchange}")
    private String exchangeName;

    @Bean
    public TopicExchange bankingEventsExchange() {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
