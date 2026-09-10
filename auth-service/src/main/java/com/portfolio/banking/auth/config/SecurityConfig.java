package com.portfolio.banking.auth.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JOSEException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;

/**
 * Nearly every endpoint here IS the public entry point - register, login,
 * minting a service token, publishing the public key - and none of them can
 * demand a token, because a token is what they exist to hand out.
 * <p>
 * {@code POST /api/v1/auth/password} is the exception, and the reason this
 * service now validates JWTs at all: changing a password is something an
 * already-signed-in user does. So auth-service becomes a resource server for
 * exactly one route, and everything else stays open.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Note the ordering: /password requires a token, while
                        // /password/forgot and /password/reset must not - they
                        // are for people who cannot sign in. Spring matches in
                        // declaration order, and an "/api/v1/auth/password/**"
                        // written first would have locked out exactly the users
                        // the reset flow exists for.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/password").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                .build();
    }

    /**
     * Built from the in-memory keypair rather than by fetching
     * {@code /.well-known/jwks.json} over HTTP the way the other three
     * services do.
     * <p>
     * They have no choice - the key lives in a different process. This one
     * already holds it, and a service resolving its own JWK set over the
     * network would add a startup dependency on itself: a loopback call that
     * can fail, time out, or race the web server it is calling.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey rsaKey) throws JOSEException {
        RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
