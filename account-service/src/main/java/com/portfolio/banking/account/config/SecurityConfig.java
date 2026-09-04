package com.portfolio.banking.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Validates the JWT on every request (via {@code jwk-set-uri} in
 * application.yml - no secret ever shared with auth-service). What the
 * caller is then allowed to do splits in two:
 * <ul>
 *   <li>{@code /credit} and {@code /debit} require {@code ROLE_SERVICE}
 *       specifically. A transfer's destination account can never pass an
 *       ownership check (it belongs to someone else by definition), so these
 *       two endpoints are not directly reachable by an end user at all -
 *       only transaction-service, holding its own service credential, calls
 *       them. See "Authentication" in the README.</li>
 *   <li>Everything else under {@code /api/v1/accounts/**} just requires
 *       being authenticated. Ownership (does this account belong to the
 *       caller?) is checked in {@code AccountController} itself, since it
 *       needs to read the account first to know who owns it - a URL-pattern
 *       rule here can't express that.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/credit", "/api/v1/accounts/*/debit")
                        .hasRole("SERVICE")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * account-service's tokens carry a single {@code role} string claim
     * ("USER" or "SERVICE"), not the space-delimited {@code scope}/{@code scp}
     * claim Spring Security's default {@code JwtGrantedAuthoritiesConverter}
     * looks for - so {@code hasRole("SERVICE")} needs this small converter to
     * turn that claim into a {@code ROLE_SERVICE} authority at all.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null) {
                return List.<GrantedAuthority>of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
