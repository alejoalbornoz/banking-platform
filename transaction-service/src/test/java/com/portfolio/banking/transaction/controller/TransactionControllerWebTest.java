package com.portfolio.banking.transaction.controller;

import com.portfolio.banking.transaction.config.SecurityConfig;
import com.portfolio.banking.transaction.dto.PageResponse;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.exception.CurrencyMismatchException;
import com.portfolio.banking.transaction.exception.ForbiddenException;
import com.portfolio.banking.transaction.exception.IdempotencyKeyReusedException;
import com.portfolio.banking.transaction.pagination.KeysetPage;
import com.portfolio.banking.transaction.service.ITransferService;
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
 * The HTTP layer of transaction-service with the saga mocked out. What is
 * worth pinning here is the contract a client actually sees: which caller
 * may reach which route, how a transfer's outcome maps to a status code, and
 * what each failure looks like on the wire - none of which
 * {@code TransferServiceTest} can say anything about.
 * <p>
 * Same construction as {@code AccountControllerWebTest}: the
 * {@link JwtDecoder} is mocked and the real filter chain runs, so the
 * {@code role}-claim converter in {@code SecurityConfig} is exercised rather
 * than bypassed.
 */
@WebMvcTest(TransactionController.class)
@Import(SecurityConfig.class)
class TransactionControllerWebTest {

    private static final String USER_TOKEN = "a-user-token";
    private static final String SERVICE_TOKEN = "a-service-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ITransferService transferService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final UUID callerId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();

    @BeforeEach
    void tokensDecodeToPrincipals() {
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(jwt(callerId.toString(), "USER"));
        when(jwtDecoder.decode(SERVICE_TOKEN)).thenReturn(jwt("ops", "SERVICE"));
    }

    @Test
    void withoutAToken_nothingIsReachable() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(validTransfer()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/transfers")).andExpect(status().isUnauthorized());
        verify(transferService, never()).transfer(any(), any(), any());
    }

    /**
     * The status code is part of the idempotency contract: 201 only for the
     * call that actually completed the transfer, 200 for a replayed result or
     * a failed saga. A client retrying after a timeout can tell from the
     * status alone whether its retry did anything.
     */
    @Test
    void aCompletedTransfer_is201_butAReplayedOrFailedOne_is200() throws Exception {
        when(transferService.transfer(eq(callerId.toString()), eq("k-1"), any()))
                .thenReturn(transfer("COMPLETED"));
        when(transferService.transfer(eq(callerId.toString()), eq("k-2"), any()))
                .thenReturn(transfer("FAILED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validTransfer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON).content(validTransfer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void theCallerIdentity_comesFromTheToken_notTheBody() throws Exception {
        when(transferService.transfer(anyString(), anyString(), any())).thenReturn(transfer("COMPLETED"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(validTransfer()))
                .andExpect(status().isCreated());

        verify(transferService).transfer(eq(callerId.toString()), eq("k-1"), any());
    }

    @Test
    void aTransfer_withoutAnIdempotencyKey_is400_notA500() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(validTransfer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    @Test
    void aTransfer_withAMissingAmountAndABadCurrency_namesBothFields() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + sourceId + "\",\"destinationAccountId\":\""
                                + destinationId + "\",\"currency\":\"us dollars\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        org.hamcrest.Matchers.containsInAnyOrder("amount", "currency")));
    }

    /**
     * The three business rejections, each with the status a client is
     * documented to branch on. A wrong mapping here - a 403 that came out as
     * 401, a 409 that came out as 400 - is invisible to every other test in
     * the module.
     */
    @Test
    void eachBusinessRejection_hasItsOwnStatusAndCode() throws Exception {
        when(transferService.transfer(anyString(), eq("not-mine"), any()))
                .thenThrow(new ForbiddenException("Not authorized to transfer from account " + sourceId));
        when(transferService.transfer(anyString(), eq("reused"), any()))
                .thenThrow(new IdempotencyKeyReusedException("reused"));
        when(transferService.transfer(anyString(), eq("eur-to-usd"), any()))
                .thenThrow(new CurrencyMismatchException("Transfer currency USD does not match"));

        mockMvc.perform(transferWithKey("not-mine"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mockMvc.perform(transferWithKey("reused"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(transferWithKey("eur-to-usd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"));
    }

    /**
     * The one route that spans every user's data, and therefore the one that
     * must not be reachable with a user token no matter whose.
     */
    @Test
    void stuckTransfers_areServiceOnly() throws Exception {
        when(transferService.listStuckTransfers()).thenReturn(List.of(transfer("COMPENSATION_FAILED")));

        mockMvc.perform(get("/api/v1/transfers/stuck").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/transfers/stuck").header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPENSATION_FAILED"));
    }

    /**
     * {@code /stuck} and {@code /{transactionId}} share a prefix. The literal
     * segment must win, or "stuck" gets parsed as a UUID and 400s.
     */
    @Test
    void theStuckRoute_isNotSwallowedByThePathVariableRoute() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/stuck").header("Authorization", "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk());
        verify(transferService, never()).getTransaction(anyString(), any());
    }

    @Test
    void listMyTransfers_passesTheCursorAndLimitThrough() throws Exception {
        when(transferService.listMyTransfers(eq(callerId.toString()), any()))
                .thenReturn(new PageResponse<>(List.of(transfer("COMPLETED")), "next-please"));

        mockMvc.perform(get("/api/v1/transfers").param("limit", "10")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.nextCursor").value("next-please"));

        ArgumentCaptor<KeysetPage> page = ArgumentCaptor.forClass(KeysetPage.class);
        verify(transferService).listMyTransfers(eq(callerId.toString()), page.capture());
        assertThat(page.getValue().limit()).isEqualTo(10);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transferWithKey(String key) {
        return post("/api/v1/transfers")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(validTransfer());
    }

    private String validTransfer() {
        return "{\"sourceAccountId\":\"" + sourceId + "\",\"destinationAccountId\":\"" + destinationId
                + "\",\"amount\":25.00,\"currency\":\"USD\"}";
    }

    private TransferResponse transfer(String status) {
        return new TransferResponse(UUID.randomUUID(), sourceId, destinationId, new BigDecimal("25.00"),
                "USD", status, null, Instant.now(), Instant.now());
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
}
