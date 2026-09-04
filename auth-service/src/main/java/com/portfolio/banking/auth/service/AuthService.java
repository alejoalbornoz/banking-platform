package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.config.ServiceClientsProperties;
import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.exception.EmailAlreadyExistsException;
import com.portfolio.banking.auth.exception.InvalidCredentialsException;
import com.portfolio.banking.auth.model.User;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Service
public class AuthService implements IAuthService {

    private static final String ISSUER = "auth-service";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_SERVICE = "SERVICE";

    private final IUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final ServiceClientsProperties serviceClientsProperties;
    private final long userTokenTtlSeconds;
    private final long serviceTokenTtlSeconds;

    public AuthService(IUserRepository userRepository,
                        PasswordEncoder passwordEncoder,
                        JwtEncoder jwtEncoder,
                        ServiceClientsProperties serviceClientsProperties,
                        @Value("${banking.jwt.user-token-ttl-seconds}") long userTokenTtlSeconds,
                        @Value("${banking.jwt.service-token-ttl-seconds}") long serviceTokenTtlSeconds) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.serviceClientsProperties = serviceClientsProperties;
        this.userTokenTtlSeconds = userTokenTtlSeconds;
        this.serviceTokenTtlSeconds = serviceTokenTtlSeconds;
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()));
        // saveAndFlush, not save: @CreationTimestamp is populated by
        // Hibernate when the INSERT is actually written, which a plain
        // save() defers to commit - without the flush, createdAt would come
        // back null here even though it's NOT NULL in the database.
        User saved = userRepository.saveAndFlush(user);
        return new UserResponse(saved.getId(), saved.getEmail(), saved.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
                // Same exception either way: which of "unknown email" or
                // "wrong password" actually happened is not something a
                // caller needs, or should get, to distinguish.
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        String token = encodeToken(now, userTokenTtlSeconds, claims -> claims
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", ROLE_USER));
        return new TokenResponse(token, userTokenTtlSeconds);
    }

    @Override
    public TokenResponse issueServiceToken(ServiceTokenRequest request) {
        String configuredSecret = serviceClientsProperties.getServiceClients().get(request.clientId());
        if (configuredSecret == null || !configuredSecret.equals(request.clientSecret())) {
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        String token = encodeToken(now, serviceTokenTtlSeconds, claims -> claims
                .subject(request.clientId())
                .claim("role", ROLE_SERVICE));
        return new TokenResponse(token, serviceTokenTtlSeconds);
    }

    private String encodeToken(Instant issuedAt, long ttlSeconds,
                                UnaryOperator<JwtClaimsSet.Builder> claimsCustomizer) {
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(ttlSeconds))
                .id(UUID.randomUUID().toString());
        JwtClaimsSet claims = claimsCustomizer.apply(claimsBuilder).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
