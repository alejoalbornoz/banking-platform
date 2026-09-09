package com.portfolio.banking.auth.service;

import com.portfolio.banking.auth.config.ServiceClientsProperties;
import com.portfolio.banking.auth.dto.LoginRequest;
import com.portfolio.banking.auth.dto.RefreshTokenRequest;
import com.portfolio.banking.auth.dto.RegisterRequest;
import com.portfolio.banking.auth.dto.ServiceTokenRequest;
import com.portfolio.banking.auth.dto.TokenResponse;
import com.portfolio.banking.auth.dto.UserResponse;
import com.portfolio.banking.auth.exception.EmailAlreadyExistsException;
import com.portfolio.banking.auth.exception.InvalidCredentialsException;
import com.portfolio.banking.auth.model.RefreshToken;
import com.portfolio.banking.auth.model.User;
import com.portfolio.banking.auth.repository.IRefreshTokenRepository;
import com.portfolio.banking.auth.repository.IUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Service
public class AuthService implements IAuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String ISSUER = "auth-service";
    private static final String ROLE_USER = "USER";
    private static final String ROLE_SERVICE = "SERVICE";

    /**
     * 256 bits. A refresh token is never guessed, only stolen or not - so the
     * only job of this number is to put brute force permanently out of reach,
     * and it does.
     */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final IUserRepository userRepository;
    private final LoginThrottle loginThrottle;
    private final IRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final ServiceClientsProperties serviceClientsProperties;
    private final long userTokenTtlSeconds;
    private final long serviceTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Deliberately REQUIRES_NEW, and used for exactly one thing: revoking a
     * family after reuse is detected. That revocation has to outlive the
     * failure that triggered it. It happens inside {@link #refresh}, which is
     * {@code @Transactional} and which ends by throwing - so a revocation
     * enlisted in that same transaction would be rolled back along with it,
     * leaving the compromised family live and the attack unrecorded. A
     * separate transaction commits on its own.
     */
    private final TransactionTemplate revocationTransactionTemplate;

    /** A valid bcrypt hash that no password matches - see {@code matchesOrBurnTheSameTime}. */
    private final String decoyHash;

    public AuthService(IUserRepository userRepository,
                        IRefreshTokenRepository refreshTokenRepository,
                        LoginThrottle loginThrottle,
                        PasswordEncoder passwordEncoder,
                        JwtEncoder jwtEncoder,
                        ServiceClientsProperties serviceClientsProperties,
                        PlatformTransactionManager transactionManager,
                        @Value("${banking.jwt.user-token-ttl-seconds}") long userTokenTtlSeconds,
                        @Value("${banking.jwt.service-token-ttl-seconds}") long serviceTokenTtlSeconds,
                        @Value("${banking.jwt.refresh-token-ttl-seconds}") long refreshTokenTtlSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.loginThrottle = loginThrottle;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.serviceClientsProperties = serviceClientsProperties;
        this.userTokenTtlSeconds = userTokenTtlSeconds;
        this.serviceTokenTtlSeconds = serviceTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;

        this.revocationTransactionTemplate = new TransactionTemplate(transactionManager);
        this.revocationTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // One bcrypt at startup, so an unknown address is compared against a
        // hash of the same shape and cost as a real one. Generated rather
        // than hardcoded, so it always matches whatever encoder is wired in
        // - a decoy produced by a different algorithm than the real hashes
        // would take a different amount of time and give the game away.
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * The lookup handles the ordinary case; the {@code unique (email)}
     * constraint handles the case the lookup structurally can't. Two
     * simultaneous registrations of the same address both see "not taken",
     * both insert, and only the database can arbitrate that - the same
     * division of labour as account-service's ledger. Without the catch, the
     * loser of that race gets a raw constraint violation surfaced as a 500,
     * when what actually happened is precisely the 409 this already reports
     * in the common case.
     */
    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()));
        try {
            // saveAndFlush, not save: @CreationTimestamp is populated by
            // Hibernate when the INSERT is actually written, which a plain
            // save() defers to commit - without the flush, createdAt would
            // come back null here even though it's NOT NULL in the database.
            // The flush is also what makes the violation catchable here at
            // all, rather than at commit time outside this method.
            User saved = userRepository.saveAndFlush(user);
            return new UserResponse(saved.getId(), saved.getEmail(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException emailTakenConcurrently) {
            throw new EmailAlreadyExistsException(email);
        }
    }

    /**
     * Starts a new token family. Logging in twice from two devices therefore
     * produces two independent families, so revoking one - by a logout, or
     * because one device's token was stolen - leaves the other signed in.
     * <p>
     * Every path out of here is deliberately indistinguishable from the
     * others, in the response <em>and</em> on the clock. Returning the same
     * {@link InvalidCredentialsException} for an unknown address and for a
     * wrong password only hides which happened if the two also take the same
     * time, and until {@link #matchesOrBurnTheSameTime} they did not: an
     * unknown address returned before any hashing, a known one after ~100ms
     * of bcrypt. That gap is a perfectly usable oracle for reading a user list
     * out of a service that never returns one, and it needs no repeated
     * attempts to exploit - just a stopwatch.
     */
    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();
        Instant now = Instant.now();

        // Before the password is looked at, so a throttled address costs no
        // bcrypt: otherwise rejecting an attacker is more expensive for us
        // than making the attempt is for them.
        loginThrottle.assertNotThrottled(email, now);

        Optional<User> user = userRepository.findByEmail(email);
        if (!matchesOrBurnTheSameTime(request.password(), user)) {
            loginThrottle.recordFailure(email, now);
            throw new InvalidCredentialsException();
        }

        loginThrottle.recordSuccess(email);
        return issueTokenPair(user.orElseThrow(), UUID.randomUUID(), now);
    }

    /**
     * Verifies the password, and takes just as long to fail for an address
     * that does not exist as for one that does.
     * <p>
     * The comparison against {@link #decoyHash} is guaranteed to fail - that
     * is not the point of making it. It is there so the work is done either
     * way, since the whole cost of a login is the hash comparison and skipping
     * it is loudly visible from outside.
     */
    private boolean matchesOrBurnTheSameTime(String presentedPassword, Optional<User> user) {
        String hashToCompareAgainst = user.map(User::getPasswordHash).orElse(decoyHash);
        boolean matches = passwordEncoder.matches(presentedPassword, hashToCompareAgainst);
        return matches && user.isPresent();
    }

    /**
     * Exchanges a refresh token for a new pair, and rotates: the presented
     * token is consumed, and its replacement joins the same family.
     * <p>
     * <b>Rotation is what makes theft detectable.</b> Because every exchange
     * replaces the token, a given refresh token can only ever be used once by
     * an honest client. So a second use means two parties hold the same
     * token, and one of them stole it. Which one is presenting it now is
     * unknowable - the thief may be racing ahead of the victim, or replaying
     * behind them - so the only safe response is to distrust the whole
     * family and make everyone log in again. That is strictly better than the
     * alternative it replaces: without rotation, a stolen refresh token is a
     * silent, renewable, permanent session, and nothing ever reveals it.
     * <p>
     * The detection itself is not the {@code usedAt} read below - there isn't
     * one. It is {@code consume} returning zero, because a check-then-act
     * cannot arbitrate a race it is a participant in, exactly as with
     * account-service's ledger constraint.
     * <p>
     * <b>The cost, stated plainly:</b> a client that fires two refreshes
     * concurrently with the same token trips this and gets logged out, even
     * though nothing was stolen. Real deployments often soften that with a
     * short grace window in which the immediately-preceding token is accepted
     * once more. That is a deliberate trade of security for convenience, and
     * this project takes the strict side: a false logout costs a login, while
     * a missed detection costs the account.
     */
    @Override
    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash(request.refreshToken()))
                .orElseThrow(InvalidCredentialsException::new);

        Instant now = Instant.now();
        // Expiry and revocation are one-way states, so reading them from a
        // snapshot is safe: neither can become false after being true. An
        // expired token is not evidence of anything - it is the mechanism
        // working - so it does not revoke the family.
        if (presented.isExpired(now) || presented.isRevoked()) {
            throw new InvalidCredentialsException();
        }

        if (refreshTokenRepository.consume(presented.getId(), now) == 0) {
            revokeCompromisedFamily(presented, now);
            throw new InvalidCredentialsException();
        }

        // A user removed between issuing and refreshing has no identity left
        // to mint a token for.
        User user = userRepository.findById(presented.getUserId())
                .orElseThrow(InvalidCredentialsException::new);
        return issueTokenPair(user, presented.getFamilyId(), now);
    }

    /**
     * Idempotent, and deliberately silent about whether the token existed. A
     * logout that reported "unknown token" would answer, for anyone holding a
     * guess, whether that guess is a real session. Repeating a logout, or
     * sending one for a token that was already revoked, is simply a no-op -
     * the caller asked for a state, not for a change.
     */
    @Override
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByTokenHash(hash(request.refreshToken()))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    @Override
    public TokenResponse issueServiceToken(ServiceTokenRequest request) {
        String configuredSecret = serviceClientsProperties.getServiceClients().get(request.clientId());
        if (configuredSecret == null || !configuredSecret.equals(request.clientSecret())) {
            throw new InvalidCredentialsException();
        }

        Instant now = Instant.now();
        // No refresh token: a service holds its own client-id/secret and can
        // ask for another whenever it likes, so a second long-lived
        // credential would be one more thing to store and steal, buying
        // nothing. See ServiceTokenProvider in transaction-service.
        String token = encodeToken(now, serviceTokenTtlSeconds, claims -> claims
                .subject(request.clientId())
                .claim("role", ROLE_SERVICE));
        return new TokenResponse(token, serviceTokenTtlSeconds);
    }

    /**
     * Logged rather than only recorded, because a family being revoked this
     * way is a security event: somebody held a token they should not have.
     * The token never appears in the log - the family and user ids are what
     * an investigation needs, and they are not credentials.
     * <p>
     * As with transaction-service's stuck-transfer alert, the last mile is
     * missing: nothing here pages anyone. See "Known gaps" in the README.
     */
    private void revokeCompromisedFamily(RefreshToken presented, Instant now) {
        revocationTransactionTemplate.executeWithoutResult(status ->
                refreshTokenRepository.revokeFamily(presented.getFamilyId(), now));
        log.warn("Refresh token reuse detected - revoking token family {} for user {}. "
                        + "Either the token was stolen, or a client refreshed twice with the same token; "
                        + "both look identical from here.",
                presented.getFamilyId(), presented.getUserId());
    }

    private TokenResponse issueTokenPair(User user, UUID familyId, Instant now) {
        String accessToken = encodeToken(now, userTokenTtlSeconds, claims -> claims
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", ROLE_USER));

        String refreshToken = generateRefreshToken();
        refreshTokenRepository.save(new RefreshToken(
                hash(refreshToken), user.getId(), familyId, now.plusSeconds(refreshTokenTtlSeconds)));

        return new TokenResponse(accessToken, userTokenTtlSeconds, refreshToken, refreshTokenTtlSeconds);
    }

    /** Base64url so the token survives a JSON body and a header untouched. */
    private String generateRefreshToken() {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * SHA-256, not bcrypt, and the difference matters twice.
     * <p>
     * A password hash is deliberately slow because passwords are guessable
     * and an attacker with the table will try. There is nothing to guess in
     * {@link #REFRESH_TOKEN_BYTES} bytes of {@code SecureRandom}, so the work
     * factor would buy no security and cost latency on every refresh.
     * <p>
     * The second reason is decisive on its own: bcrypt salts each hash, and a
     * salted hash cannot be looked up - only compared, one row at a time,
     * against every row in the table. An unsalted digest is what lets the
     * presented token find its row in a single indexed hit.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException sha256IsMandatoryInEveryJvm) {
            throw new IllegalStateException(sha256IsMandatoryInEveryJvm);
        }
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
