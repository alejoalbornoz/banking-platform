package com.portfolio.banking.account.messaging;

import com.portfolio.banking.common.event.AccountCreatedEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountEventPublisher implements IAccountEventPublisher {

    private static final String ROUTING_KEY = "account.created";

    private final RabbitTemplate rabbitTemplate;
    private final String exchangeName;

    public AccountEventPublisher(RabbitTemplate rabbitTemplate,
                                  @Value("${banking.rabbitmq.exchange}") String exchangeName) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchangeName = exchangeName;
    }

    @Override
    public void publishAccountCreated(AccountCreatedEvent event) {
        rabbitTemplate.convertAndSend(exchangeName, ROUTING_KEY, event);
    }
}
