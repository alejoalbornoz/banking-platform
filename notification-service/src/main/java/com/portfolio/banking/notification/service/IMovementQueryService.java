package com.portfolio.banking.notification.service;

import com.portfolio.banking.notification.dto.MovementResponse;
import com.portfolio.banking.notification.dto.PageResponse;
import com.portfolio.banking.notification.pagination.KeysetPage;

import java.util.UUID;

/**
 * The read side of the movements read model.
 * <p>
 * Every query is scoped to the caller by construction - the owner is a
 * column in the projection, taken from the token - so there is no ownership
 * check to get wrong and no call to account-service to make. That is the
 * point of having projected ownership at all.
 */
public interface IMovementQueryService {

    /**
     * @param callerId  the authenticated user, from their JWT subject
     * @param accountId narrows to one of the caller's accounts; null for all
     *                   of them. An account the caller does not own simply
     *                   matches nothing, since the owner filter is applied
     *                   first - it cannot leak.
     */
    PageResponse<MovementResponse> listForOwner(String callerId, UUID accountId, KeysetPage page);
}
