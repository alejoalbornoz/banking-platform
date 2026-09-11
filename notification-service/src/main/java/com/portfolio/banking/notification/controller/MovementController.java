package com.portfolio.banking.notification.controller;

import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.pagination.KeysetPage;
import com.portfolio.banking.notification.service.IMovementQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The one merged statement: every movement across all of the caller's
 * accounts, newest first, with counterparties - the view the README listed
 * as missing since the ledger and the transfer history were written.
 * <p>
 * Served by notification-service because this is where the events already
 * arrive and where the idempotent consumption already lives. The name is a
 * wart: a service that projects two read models is a query service, not a
 * notification service. Renaming a module touches compose, the gateway, the
 * Dockerfile, CI and the README for no behavioural change, so it stays - but
 * it is a wart, not a design.
 */
@RestController
@RequestMapping("/api/v1/movements")
public class MovementController {

    private final IMovementQueryService movementQueryService;

    public MovementController(IMovementQueryService movementQueryService) {
        this.movementQueryService = movementQueryService;
    }

    /**
     * No ownership check, and none needed: the query is scoped to the token's
     * subject as a column filter. An {@code accountId} the caller does not
     * own does not 403 - it matches nothing, which reveals nothing.
     */
    @GetMapping
    public PageResponse<MovementResponse> listMovements(@AuthenticationPrincipal Jwt caller,
                                                         @RequestParam(required = false) UUID accountId,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(required = false) Integer limit) {
        return movementQueryService.listForOwner(caller.getSubject(), accountId, KeysetPage.of(cursor, limit));
    }
}
