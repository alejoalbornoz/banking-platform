package com.portfolio.banking.account.controller;

import com.portfolio.banking.account.dto.AccountResponse;
import com.portfolio.banking.account.dto.AmountRequest;
import com.portfolio.banking.account.dto.CreateAccountRequest;
import com.portfolio.banking.account.dto.LedgerResponse;
import com.portfolio.banking.account.dto.PageResponse;
import com.portfolio.banking.account.exception.ForbiddenException;
import com.portfolio.banking.account.pagination.KeysetPage;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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

    /**
     * Paginated like every other list on this platform, even though most
     * people hold two or three accounts. The response shape is what makes an
     * endpoint safe to grow into: adding a cursor later is a breaking change
     * for every client already parsing a bare array, so the cheap moment to
     * decide is now, not once someone has ten thousand of something.
     */
    @GetMapping
    public PageResponse<AccountResponse> listAccounts(@AuthenticationPrincipal Jwt caller,
                                                        @RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) Integer limit) {
        return accountService.listAccountsByOwner(callerUuid(caller), KeysetPage.of(cursor, limit));
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

    /**
     * Freezing and reactivating are restricted to {@code ROLE_SERVICE} in
     * {@code SecurityConfig}, not to the account's owner - and that asymmetry
     * with {@code /close} below is the point. A freeze is a compliance or
     * operations action taken <em>about</em> someone; letting the account
     * holder lift their own freeze would defeat the entire reason for
     * applying one.
     * <p>
     * Neither takes an {@code Idempotency-Key}, unlike credit and debit:
     * these ask for a state rather than for a change, so repeating one lands
     * in the same place.
     */
    @PostMapping("/{accountId}/freeze")
    public AccountResponse freeze(@PathVariable UUID accountId) {
        return accountService.freeze(accountId);
    }

    @PostMapping("/{accountId}/reactivate")
    public AccountResponse reactivate(@PathVariable UUID accountId) {
        return accountService.reactivate(accountId);
    }

    /**
     * Closing, by contrast, is the holder's own decision about their own
     * account, so it's authorized by ownership like the reads above. It's
     * also final and refuses a non-zero balance - money in a closed account
     * would be stranded, since it can be neither debited nor credited
     * afterwards.
     */
    @PostMapping("/{accountId}/close")
    public AccountResponse close(@AuthenticationPrincipal Jwt caller, @PathVariable UUID accountId) {
        assertOwnerOrService(caller, accountService.getAccount(accountId).ownerId());
        return accountService.close(accountId);
    }

    /**
     * One page of the account's statement, with a balance recomputed over the
     * whole ledger to prove it reconciles - the reconciliation does not narrow
     * to the page being read.
     */
    @GetMapping("/{accountId}/ledger")
    public LedgerResponse getLedger(@AuthenticationPrincipal Jwt caller,
                                      @PathVariable UUID accountId,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        assertOwnerOrService(caller, accountService.getAccount(accountId).ownerId());
        return accountService.getLedger(accountId, KeysetPage.of(cursor, limit));
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
