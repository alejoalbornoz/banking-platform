package com.portfolio.banking.transaction.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** See account-service's equivalent for why the bearer scheme is declared here. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI transactionServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("transaction-service API")
                        .version("v1")
                        .description("""
                                Transfers between accounts, run as a saga with one compensating action.

                                `Idempotency-Key` is required on every transfer: retrying with the same \
                                key replays the stored result instead of moving money twice, and reusing \
                                one with different contents is refused outright. You must own the source \
                                account, and both accounts' currency must match the transfer's.

                                `GET /api/v1/transfers/stuck` is the operations view behind the \
                                stuck-transfer alert and needs the SERVICE role."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
