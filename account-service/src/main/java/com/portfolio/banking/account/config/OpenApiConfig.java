package com.portfolio.banking.account.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the bearer-token scheme as well as the description, so the
 * generated page is something you can actually make calls from rather than
 * only read: every endpoint here needs a JWT, and without this the "Try it
 * out" button can only ever produce a 401. Paste a token from
 * {@code POST /api/v1/auth/login} into Authorize and the rest works.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI accountServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("account-service API")
                        .version("v1")
                        .description("""
                                Accounts and balances - the source of truth for how much money exists.

                                `ownerId` is never accepted from the client: an account belongs to \
                                whoever's token created it. Reads are restricted to that owner, and \
                                `/credit` and `/debit` are internal operations requiring the SERVICE \
                                role, since crediting a transfer's destination can never pass an \
                                ownership check. Move money through transaction-service instead."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
