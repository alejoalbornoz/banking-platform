package com.portfolio.banking.notification.controller;

import com.portfolio.banking.notification.config.SecurityConfig;
import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.service.IMovementQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MovementController.class)
@Import(SecurityConfig.class)
class MovementControllerWebTest {

    private static final String USER_TOKEN = "a-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IMovementQueryService movementQueryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void tokenDecodesToPrincipal() {
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(Jwt.withTokenValue("irrelevant")
                .header("alg", "RS256").subject(callerId.toString()).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
    }

    @Test
    void withoutAToken_is401() throws Exception {
        mockMvc.perform(get("/api/v1/movements")).andExpect(status().isUnauthorized());
        verify(movementQueryService, never()).listForOwner(anyString(), any(), any());
    }

    @Test
    void theOwnerScope_comesFromTheToken_andAccountIdIsOptional() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID counterparty = UUID.randomUUID();
        when(movementQueryService.listForOwner(eq(callerId.toString()), isNull(), any()))
                .thenReturn(new PageResponse<>(List.of(new MovementResponse(
                        UUID.randomUUID(), accountId, "RECEIVED", new BigDecimal("25.00"), "USD",
                        counterparty, UUID.randomUUID(), Instant.now())), "more"));

        mockMvc.perform(get("/api/v1/movements").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].counterpartyAccountId").value(counterparty.toString()))
                .andExpect(jsonPath("$.nextCursor").value("more"));
    }

    @Test
    void anAccountIdFilter_andPaging_reachTheService() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(movementQueryService.listForOwner(anyString(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), null));

        mockMvc.perform(get("/api/v1/movements")
                        .param("accountId", accountId.toString()).param("limit", "7")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<KeysetPage> page = ArgumentCaptor.forClass(KeysetPage.class);
        verify(movementQueryService).listForOwner(eq(callerId.toString()), eq(accountId), page.capture());
        assertThat(page.getValue().limit()).isEqualTo(7);
    }

    @Test
    void aMalformedCursor_is400() throws Exception {
        mockMvc.perform(get("/api/v1/movements").param("cursor", "nope!")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
