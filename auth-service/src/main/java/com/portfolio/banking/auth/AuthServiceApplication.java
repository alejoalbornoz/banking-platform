package com.portfolio.banking.auth;

import com.portfolio.banking.auth.config.ServiceClientsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ServiceClientsProperties.class)
// Drives RefreshTokenCleanup - rotation adds a refresh_tokens row per
// refresh, so something has to remove the long-dead ones.
@EnableScheduling
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
