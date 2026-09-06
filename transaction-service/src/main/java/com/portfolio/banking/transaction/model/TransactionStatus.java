package com.portfolio.banking.transaction.model;

public enum TransactionStatus {
    /** Row created, nothing has been sent to account-service yet. */
    PENDING,
    /** Source account debited; about to credit the destination. */
    DEBITED,
    /** Both legs succeeded. Terminal, successful state. */
    COMPLETED,
    /** Either leg failed before any money moved irreversibly, or it moved
     *  and was successfully compensated. Terminal, unsuccessful state. */
    FAILED,
    /** Debit succeeded, credit failed, AND the compensating credit-back
     *  also failed. Money is now stuck mid-transfer. Terminal, but requires
     *  manual/ops intervention: entering this state raises an alert (see
     *  IStuckTransferAlerter) rather than only sitting in a database, and
     *  GET /api/v1/transfers/stuck lists everything currently in it. */
    COMPENSATION_FAILED
}
