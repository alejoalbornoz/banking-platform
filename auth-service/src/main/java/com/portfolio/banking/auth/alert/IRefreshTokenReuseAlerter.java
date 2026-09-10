package com.portfolio.banking.auth.alert;

import java.util.UUID;

/**
 * Raises the alert for a refresh token presented twice - which means two
 * parties hold it, and one of them stole it.
 * <p>
 * An interface for the same reason {@code IStuckTransferAlerter} is one in
 * transaction-service: this is the seam a real deployment replaces.
 * {@link RefreshTokenReuseAlerter} emits a counter and a marked log line,
 * which is what metric- and log-based alerting hook onto; swapping in a
 * pager, a SIEM, or an account-security workflow is implementing one method.
 * <p>
 * Implementations must not throw. The caller has already revoked the family
 * by the time this runs, and an unreachable alerting channel must not turn a
 * handled security event into a failed request.
 */
public interface IRefreshTokenReuseAlerter {

    /**
     * @param familyId the token family being revoked - the unit of
     *                  compromise, and what an investigation follows
     * @param userId   whose session it was
     */
    void alert(UUID familyId, UUID userId);
}
