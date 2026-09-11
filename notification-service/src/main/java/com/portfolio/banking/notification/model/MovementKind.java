package com.portfolio.banking.notification.model;

/**
 * Money that actually moved, from one account's point of view.
 * <p>
 * There is no FAILED kind on purpose. A failed transfer moved nothing, or
 * moved something and was undone; either way the account's money is where it
 * was, and telling the holder about the attempt is a notification's job, not
 * a statement line.
 */
public enum MovementKind {
    /** The opening balance, from account.created. */
    OPENING,
    /** This account was the source of a completed transfer. */
    SENT,
    /** This account was the destination of a completed transfer. */
    RECEIVED
}
