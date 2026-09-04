package com.portfolio.banking.account.controller;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.AmountRequest;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.exception.ForbiddenException;
import com.portfolio.banking.account.service.IAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final String ROLE_SERVICE = "SERVICE";

    private final IAccountService accountService;

    public AccountController(IAccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@AuthenticationPrincipal Jwt caller,
                                                           @Valid @RequestBody CreateAccountRequest request,
                                                           UriComponentsBuilder uriBuilder) {
        AccountResponse created = accountService.createAccount(callerUuid(caller), request);
        URI location = uriBuilder.path("/api/v1/accounts/{id}").build(created.id());
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@AuthenticationPrincipal Jwt caller, @PathVariable UUID accountId) {
        AccountResponse account = accountService.getAccount(accountId);
        assertOwnerOrService(caller, account.ownerId());
        return account;
    }

    @GetMapping("/number/{accountNumber}")
    public AccountResponse getAccountByNumber(@AuthenticationPrincipal Jwt caller, @PathVariable String accountNumber) {
        AccountResponse account = accountService.getAccountByNumber(accountNumber);
        assertOwnerOrService(caller, account.ownerId());
        return account;
    }

    @GetMapping
    public List<AccountResponse> listAccounts(@AuthenticationPrincipal Jwt caller) {
        return accountService.listAccountsByOwner(callerUuid(caller));
    }

    /**
     * The {@code Idempotency-Key} header is required, not optional. Anything
     * that moves money has to be safely retryable, and a caller that can't
     * retry after a timeout has no good options: it either risks double
     * -crediting or gives up on a request that may well have succeeded.
     * Making the header mandatory means that situation can't arise.
     * <p>
     * The key is scoped to this account, so a transfer can reuse one
     * transaction id across its two legs without them colliding.
     */
    @PostMapping("/{accountId}/credit")
    public AccountResponse credit(@PathVariable UUID accountId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody AmountRequest request) {
        return accountService.credit(accountId, idempotencyKey, request.amount());
    }

    @PostMapping("/{accountId}/debit")
    public AccountResponse debit(@PathVariable UUID accountId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @Valid @RequestBody AmountRequest request) {
        return accountService.debit(accountId, idempotencyKey, request.amount());
    }

    /** The account's statement, with a recomputed balance to prove it reconciles. */
    @GetMapping("/{accountId}/ledger")
    public LedgerResponse getLedger(@AuthenticationPrincipal Jwt caller, @PathVariable UUID accountId) {
        assertOwnerOrService(caller, accountService.getAccount(accountId).ownerId());
        return accountService.getLedger(accountId);
    }

    private static String callerId(Jwt caller) {
        return caller.getSubject();
    }

    private static UUID callerUuid(Jwt caller) {
        return UUID.fromString(callerId(caller));
    }

    /**
     * Whoever holds a {@code ROLE_SERVICE} token (transaction-service, using
     * its own service credential) can read any account's details - it needs
     * to, to verify a transfer's source account ownership before ever
     * touching this service's actual balance-changing endpoints. Every other
     * caller can only read their own.
     */
    private void assertOwnerOrService(Jwt caller, UUID resourceOwnerId) {
        boolean isOwner = callerId(caller).equals(resourceOwnerId.toString());
        boolean isService = ROLE_SERVICE.equals(caller.getClaimAsString("role"));
        if (!isOwner && !isService) {
            throw new ForbiddenException("Not authorized to access this account");
        }
    }
}
