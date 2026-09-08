package com.portfolio.banking.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * No bearer scheme here, unlike the other three services: these endpoints are
 * where a token comes from, so requiring one to call them would be circular.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("auth-service API")
                        .version("v1")
                        .description("""
                                Registration, login, and the JWTs the rest of the platform runs on.

                                Tokens are signed with RS256 using a keypair generated at startup; \
                                the public half is published at `/.well-known/jwks.json`, and every \
                                other service validates signatures against it rather than sharing a \
                                secret. Start at `POST /api/v1/auth/login` - the token it returns is \
                                what the other services' Authorize buttons expect.

                                `POST /api/v1/auth/service-token` is for trusted internal callers \
                                (transaction-service, notification-service), not end users."""));
    }
}
