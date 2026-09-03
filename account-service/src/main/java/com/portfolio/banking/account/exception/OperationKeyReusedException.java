package com.portfolio.banking.account.exception;

/**
 * Thrown when an idempotency key that already posted one operation to an
 * account is presented again for a <em>different</em> one (different amount
 * or direction).
 * <p>
 * The safe options here are both bad: applying it would break the promise the
 * key makes, and replaying the original would silently do something other than
 * what this caller asked for. Since we can't tell which the client meant, we
 * refuse and let them decide - a 409 they can see beats a wrong balance they
 * can't.
 */
public class OperationKeyReusedException extends ConflictException {

    private static final String ERROR_CODE = "OPERATION_KEY_REUSED";

    public OperationKeyReusedException(String operationKey) {
        super(ERROR_CODE, "Idempotency-Key '" + operationKey
                + "' was already used on this account for a different operation");
    }
}
