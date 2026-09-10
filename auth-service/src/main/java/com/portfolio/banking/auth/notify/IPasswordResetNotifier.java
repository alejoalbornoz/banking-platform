package com.portfolio.banking.auth.notify;

/**
 * Delivers a reset token to whoever owns the address - the one step of the
 * flow that leaves this system.
 * <p>
 * A seam, like the alerters: the shipped implementation sends real SMTP to
 * the mail catcher in {@code docker-compose.yml}, and a deployment
 * substitutes a transactional-email provider by implementing one method.
 * <p>
 * Implementations must not throw. The token row is already committed by the
 * time this runs, and more importantly the caller must answer identically
 * whether or not the address exists - so a mail failure cannot be allowed to
 * change the response, or the error itself becomes the oracle.
 */
public interface IPasswordResetNotifier {

    /**
     * @param email the address that asked, which is by definition a real
     *               registered one - this is never called for an address that
     *               does not exist
     * @param token the raw token, which exists in memory only here and in the
     *               message this sends. It is never logged and only its hash
     *               is stored.
     */
    void sendPasswordReset(String email, String token);
}
