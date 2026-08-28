package com.portfolio.banking.account.messaging;

import com.portfolio.banking.common.event.AccountCreatedEvent;

public interface IAccountEventPublisher {

    void publishAccountCreated(AccountCreatedEvent event);
}
