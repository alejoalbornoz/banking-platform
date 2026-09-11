package com.portfolio.banking.account.controller;

import com.portfolio.banking.account.config.SecurityConfig;
import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.PageResponse;
import com.portfolio.banking.account.pagination.KeysetPage;
import com.portfolio.banking.account.service.IAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP layer with the service mocked out: the security rules, the
 * ownership check, request validation, and the wire shapes.
 * <p>
 * Everything here used to be covered only by {@code AccountIT}, which needs
 * Docker and therefore runs only in CI. That left a class of bug that unit
 * tests cannot see and that only surfaced after a push: a rule in
 * {@code SecurityConfig} attached to the wrong path, a missing dependency
 * that stops the filter chain from being built at all, a validation
 * annotation that returns the wrong status. This slice starts the real
 * filter chain and the real controller under plain {@code mvn test}, with no
 * database behind them.
 * <p>
 * The {@link JwtDecoder} is mocked rather than the security filter being
 * bypassed, so every request goes through the real
 * {@code BearerTokenAuthenticationFilter} and the real
 * {@code JwtAuthenticationConverter} in {@code SecurityConfig} - the one
 * that maps the plain-string {@code role} claim to a {@code ROLE_*}
 * authority. Injecting an already-built authentication would test everything
 * except that converter, and the converter is the part most likely to be
 * wrong.
 */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerWebTest {

    private static final String USER_TOKEN = "a-user-token";
    private static final String SERVICE_TOKEN = "a-service-token";
    private static final String OTHER_USER_TOKEN = "somebody-elses-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IAccountService accountService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void tokensDecodeToPrincipals() {
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(jwt(ownerId.toString(), "USER"));
        when(jwtDecoder.decode(OTHER_USER_TOKEN)).thenReturn(jwt(UUID.randomUUID().toString(), "USER"));
        when(jwtDecoder.decode(SERVICE_TOKEN)).thenReturn(jwt("transaction-service", "SERVICE"));
    }

    // --- who may do what --------------------------------------------------

    @Test
    void withoutAToken_everyEndpointIs401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/" + accountId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/credit")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The rule that makes the whole authorization model work: a transfer's
     * destination never belongs to the caller, so crediting it can never pass
     * an ownership check. Only a service credential may move money directly.
     */
    @Test
    void creditAndDebit_areServiceOnly_evenForTheAccountsOwner() throws Exception {
        for (String leg : List.of("credit", "debit")) {
            mockMvc.perform(post("/api/v1/accounts/" + accountId + "/" + leg)
                            .header("Authorization", "Bearer " + USER_TOKEN)
                            .header("Idempotency-Key", "k-1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                    .andExpect(status().isForbidden());
        }
        verify(accountService, never()).credit(any(), anyString(), any());
        verify(accountService, never()).debit(any(), anyString(), any());
    }

    @Test
    void credit_withAServiceToken_reachesTheService() throws Exception {
        when(accountService.credit(eq(accountId), eq("k-1"), any())).thenReturn(account(ownerId, "110.00"));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/credit")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(110.00));
    }

    /**
     * Freeze is done ABOUT an account holder, not by them - a holder who can
     * lift their own freeze is not frozen. Close is the holder's own call.
     * The asymmetry is the point, and it is expressed in two different
     * places (a URL rule for freeze, a controller check for close), which is
     * exactly the kind of thing that drifts.
     */
    @Test
    void freeze_isServiceOnly_butClose_isTheOwnersCall() throws Exception {
        when(accountService.getAccount(accountId)).thenReturn(account(ownerId, "0.00"));
        when(accountService.close(accountId)).thenReturn(account(ownerId, "0.00"));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/freeze")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/close")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void readingSomebodyElsesAccount_is403_beforeAnythingIsReturned() throws Exception {
        when(accountService.getAccount(accountId)).thenReturn(account(ownerId, "100.00"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + OTHER_USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"))
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    void aServiceToken_mayReadAnyAccount() throws Exception {
        // transaction-service needs this to check who owns a transfer's
        // source before touching a balance.
        when(accountService.getAccount(accountId)).thenReturn(account(ownerId, "100.00"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk());
    }

    // --- what the request has to look like ---------------------------------

    @Test
    void createAccount_takesTheOwnerFromTheToken_neverFromTheBody() throws Exception {
        when(accountService.createAccount(eq(ownerId), any())).thenReturn(account(ownerId, "100.00"));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        // An ownerId in the body must be ignored, not honoured.
                        .content("{\"openingBalance\":100.00,\"currency\":\"USD\",\"ownerId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()));

        verify(accountService).createAccount(eq(ownerId), any());
    }

    @Test
    void createAccount_rejectsANegativeBalanceAndABadCurrency_namingTheField() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalance\":-1.00,\"currency\":\"dollars\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("openingBalance", "currency")));

        verify(accountService, never()).createAccount(any(), any());
    }

    /**
     * Without the dedicated handler a missing header falls through to the
     * catch-all and becomes a 500 - telling the caller the server broke when
     * their request did.
     */
    @Test
    void credit_withoutAnIdempotencyKey_is400_notA500() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/credit")
                        .header("Authorization", "Bearer " + SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    // --- pagination plumbing ------------------------------------------------

    @Test
    void listAccounts_passesCursorAndLimitThrough_andClampsTheLimit() throws Exception {
        when(accountService.listAccountsByOwner(eq(ownerId), any()))
                .thenReturn(new PageResponse<>(List.of(), null));

        mockMvc.perform(get("/api/v1/accounts").param("limit", "5000")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        ArgumentCaptor<KeysetPage> page = ArgumentCaptor.forClass(KeysetPage.class);
        verify(accountService).listAccountsByOwner(eq(ownerId), page.capture());
        assertThat(page.getValue().limit()).isEqualTo(KeysetPage.MAX_LIMIT);
        assertThat(page.getValue().isFirstPage()).isTrue();
    }

    @Test
    void aMalformedCursor_is400WithAMessageThatSaysWhatToDo() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("cursor", "not-a-cursor!!")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("omit it to start from the first page")));
    }

    private static Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("irrelevant")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    private AccountResponse account(UUID owner, String balance) {
        return new AccountResponse(accountId, "123456789012", owner, new BigDecimal(balance),
                "USD", "ACTIVE", 0L, Instant.now(), Instant.now());
    }
}
