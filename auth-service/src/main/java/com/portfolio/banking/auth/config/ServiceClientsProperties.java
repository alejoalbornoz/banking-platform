package com.portfolio.banking.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Service-to-service credentials (client id -> secret), from
 * {@code banking.security.service-clients} in application.yml.
 * <p>
 * The rest of this codebase reads config via scattered {@code @Value}
 * fields rather than {@code @ConfigurationProperties} - this is the one
 * exception, because the config here is map-shaped (an arbitrary set of
 * client ids), which a single {@code @Value} can't bind cleanly.
 */
@ConfigurationProperties(prefix = "banking.security")
public class ServiceClientsProperties {

    private Map<String, String> serviceClients = Map.of();

    public Map<String, String> getServiceClients() {
        return serviceClients;
    }

    public void setServiceClients(Map<String, String> serviceClients) {
        this.serviceClients = serviceClients;
    }
}
