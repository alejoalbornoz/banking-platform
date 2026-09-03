package com.portfolio.banking.account.model;

import java.math.BigDecimal;

/**
 * Which way money moved in a single ledger entry, from this account's point
 * of view.
 * <p>
 * Amounts are always stored positive; the direction carries the sign. That
 * keeps the CHECK constraint {@code amount > 0} meaningful (a negative amount
 * is always a bug, never a debit) and makes the intent readable in a raw SQL
 * dump without having to remember a sign convention.
 */
public enum LedgerDirection {

    /** Money in: balance goes up. */
    CREDIT {
        @Override
        public BigDecimal signed(BigDecimal amount) {
            return amount;
        }
    },

    /** Money out: balance goes down. */
    DEBIT {
        @Override
        public BigDecimal signed(BigDecimal amount) {
            return amount.negate();
        }
    };

    /** The amount as it contributes to a balance sum. */
    public abstract BigDecimal signed(BigDecimal amount);
}
