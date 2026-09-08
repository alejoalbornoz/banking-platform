package com.portfolio.banking.transaction.controller;

import com.portfolio.banking.transaction.dto.PageResponse;
import com.portfolio.banking.transaction.dto.TransferRequest;
import com.portfolio.banking.transaction.dto.TransferResponse;
import com.portfolio.banking.transaction.pagination.KeysetPage;
import com.portfolio.banking.transaction.service.ITransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransactionController {

    private final ITransferService transferService;

    public TransactionController(ITransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal Jwt caller,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(caller.getSubject(), idempotencyKey, request);
        return ResponseEntity.status(statusFor(response)).body(response);
    }

    @GetMapping("/{transactionId}")
    public TransferResponse getTransaction(@AuthenticationPrincipal Jwt caller, @PathVariable UUID transactionId) {
        return transferService.getTransaction(caller.getSubject(), transactionId);
    }

    /**
     * The caller's own transfer history: the ones they sent, newest first.
     * <p>
     * Money that arrived is not here, and that is a scoping decision rather
     * than an oversight - a received transfer is visible in the destination
     * account's ledger and in its {@code TRANSFER_RECEIVED} notification.
     * {@code TransferService.listMyTransfers} explains why answering
     * "everything touching my accounts" from this service would cost one
     * network call per row.
     */
    @GetMapping
    public PageResponse<TransferResponse> listMyTransfers(@AuthenticationPrincipal Jwt caller,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer limit) {
        return transferService.listMyTransfers(caller.getSubject(), KeysetPage.of(cursor, limit));
    }

    /**
     * The ops view behind the stuck-transfer alert: every transfer currently
     * in COMPENSATION_FAILED, across all users. Restricted to
     * {@code ROLE_SERVICE} in {@code SecurityConfig} - it's the one endpoint
     * here that isn't scoped to the caller's own accounts.
     * <p>
     * The literal {@code /stuck} segment takes precedence over the
     * {@code /{transactionId}} mapping above, so the two don't collide.
     */
    @GetMapping("/stuck")
    public List<TransferResponse> listStuckTransfers() {
        return transferService.listStuckTransfers();
    }

    /** 201 when this call is the one that completed the transfer; 200 for anything else (failed, or a replayed result). */
    private HttpStatus statusFor(TransferResponse response) {
        return "COMPLETED".equals(response.status()) ? HttpStatus.CREATED : HttpStatus.OK;
    }
}
