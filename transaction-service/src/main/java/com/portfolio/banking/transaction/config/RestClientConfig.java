package com.portfolio.banking.transaction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient accountServiceRestClient(RestClient.Builder builder,
                                                @Value("${services.account-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
