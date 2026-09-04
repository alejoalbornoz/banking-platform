package com.portfolio.banking.auth.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public half of auth-service's signing key, in standard JWK Set shape.
 * account-service, transaction-service, and notification-service each point
 * their {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} here
 * and Spring Security handles fetching and caching it automatically - no
 * key material is ever copied into another service's own config.
 */
@RestController
public class JwksController {

    private final RSAKey rsaKey;

    public JwksController(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }
}
