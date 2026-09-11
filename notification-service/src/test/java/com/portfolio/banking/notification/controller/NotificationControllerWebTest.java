package com.portfolio.banking.notification.controller;

import com.portfolio.banking.notification.config.SecurityConfig;
import com.portfolio.banking.notification.dto.NotificationResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.exception.ForbiddenException;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.service.INotificationService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one read endpoint this service exposes, with its security chain and
 * error mapping running for real and the service mocked. Small, because the
 * surface is small - but until now nothing under {@code mvn test} confirmed
 * that a missing token is a 401 here rather than a 500, or that the
 * ownership rejection reaches the wire as a 403.
 */
@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerWebTest {

    private static final String USER_TOKEN = "a-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private INotificationService notificationService;

    @MockBean
    private JwtDecoder jwtDecoder;

    private final UUID callerId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void tokenDecodesToPrincipal() {
        when(jwtDecoder.decode(USER_TOKEN)).thenReturn(Jwt.withTokenValue("irrelevant")
                .header("alg", "RS256").subject(callerId.toString()).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
    }

    @Test
    void withoutAToken_is401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("accountId", accountId.toString()))
                .andExpect(status().isUnauthorized());
        verify(notificationService, never()).listForAccount(anyString(), any(), any());
    }

    @Test
    void theCallerIdentity_comesFromTheToken() throws Exception {
        when(notificationService.listForAccount(eq(callerId.toString()), eq(accountId), any()))
                .thenReturn(new PageResponse<>(List.of(
                        new NotificationResponse(UUID.randomUUID(), accountId, "TRANSFER_RECEIVED",
                                "You received 25.00 USD", Instant.now())), null));

        mockMvc.perform(get("/api/v1/notifications").param("accountId", accountId.toString())
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("TRANSFER_RECEIVED"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void somebodyElsesAccount_is403OnTheWire() throws Exception {
        when(notificationService.listForAccount(anyString(), eq(accountId), any()))
                .thenThrow(new ForbiddenException("Not authorized to view notifications for this account"));

        mockMvc.perform(get("/api/v1/notifications").param("accountId", accountId.toString())
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    /**
     * This one failed the first time it ran: the missing parameter fell
     * through to the catch-all and came back as a 500. Exactly the class of
     * bug these slices exist to catch before a push, not after.
     */
    @Test
    void accountId_isRequired_andItsAbsenceIs400NotA500() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_PARAMETER"));
    }

    @Test
    void cursorAndLimit_reachTheService() throws Exception {
        when(notificationService.listForAccount(anyString(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), null));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("accountId", accountId.toString()).param("limit", "3")
                        .header("Authorization", "Bearer " + USER_TOKEN))
                .andExpect(status().isOk());

        ArgumentCaptor<KeysetPage> page = ArgumentCaptor.forClass(KeysetPage.class);
        verify(notificationService).listForAccount(eq(callerId.toString()), eq(accountId), page.capture());
        assertThat(page.getValue().limit()).isEqualTo(3);
    }
}
