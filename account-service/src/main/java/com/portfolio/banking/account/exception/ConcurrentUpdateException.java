package com.portfolio.banking.account.exception;

/**
 * Thrown when an update loses the optimistic-locking race: another
 * transaction modified the same row between our read and our write. The
 * caller is expected to retry the whole operation (re-read, re-apply,
 * re-save) rather than treat this as a permanent failure.
 */
public class ConcurrentUpdateException extends ConflictException {

    private static final String ERROR_CODE = "CONCURRENT_UPDATE";

    public ConcurrentUpdateException(String message) {
        super(ERROR_CODE, message);
    }
}
