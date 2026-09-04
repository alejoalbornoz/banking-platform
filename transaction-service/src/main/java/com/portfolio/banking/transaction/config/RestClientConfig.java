package com.portfolio.banking.transaction.config;

import com.portfolio.banking.transaction.client.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Every call this client makes needs to carry transaction-service's own
     * service token, not any particular end user's - attaching it here, once,
     * means {@code AccountClient}'s methods never have to think about it.
     */
    @Bean
    public RestClient accountServiceRestClient(RestClient.Builder builder,
                                                @Value("${services.account-service.base-url}") String baseUrl,
                                                ServiceTokenProvider serviceTokenProvider) {
        return builder.baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(serviceTokenProvider.getToken());
                    return execution.execute(request, body);
                })
                .build();
    }

    /** No auth header here - this is the endpoint that mints the service token in the first place. */
    @Bean
    public RestClient authServiceRestClient(RestClient.Builder builder,
                                             @Value("${services.auth-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
