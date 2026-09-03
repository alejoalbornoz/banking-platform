package com.portfolio.banking.account.controller;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.AmountRequest;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.service.IAccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final IAccountService accountService;

    public AccountController(IAccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request,
                                                           UriComponentsBuilder uriBuilder) {
        AccountResponse created = accountService.createAccount(request);
        URI location = uriBuilder.path("/api/v1/accounts/{id}").build(created.id());
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        return accountService.getAccount(accountId);
    }

    @GetMapping("/number/{accountNumber}")
    public AccountResponse getAccountByNumber(@PathVariable String accountNumber) {
        return accountService.getAccountByNumber(accountNumber);
    }

    @GetMapping
    public List<AccountResponse> listAccounts(@RequestParam UUID ownerId) {
        return accountService.listAccountsByOwner(ownerId);
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
    public LedgerResponse getLedger(@PathVariable UUID accountId) {
        return accountService.getLedger(accountId);
    }
}
