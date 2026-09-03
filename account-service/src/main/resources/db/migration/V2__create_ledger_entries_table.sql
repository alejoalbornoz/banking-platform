-- The ledger is the audit trail behind every balance change. Two jobs:
--
-- 1. Idempotency. (account_id, operation_key) is UNIQUE, so a retried
--    credit/debit that already committed hits the constraint instead of
--    moving money a second time. The key is scoped per account, not global,
--    because one transfer legitimately posts to two different accounts and
--    those two postings must not collide with each other.
--
-- 2. Auditability. accounts.balance is a running total kept for fast reads;
--    the entries are the source of truth it must always agree with. Because
--    each entry also records balance_after, the balance can be re-derived
--    and reconciled at any point - see the /ledger endpoint.
CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY,
    account_id    UUID NOT NULL,
    operation_key VARCHAR(255) NOT NULL,
    direction     VARCHAR(6) NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uq_ledger_entries_account_operation UNIQUE (account_id, operation_key),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_ledger_entries_balance_after_non_negative CHECK (balance_after >= 0)
);

-- Serves the "statement" read: one account's entries, newest first.
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);

-- Backfill: accounts created before the ledger existed have a balance but no
-- entries explaining it, which would make every one of them fail
-- reconciliation. Give each a synthetic opening entry so
-- "balance = sum(entries)" holds for every account, not just new ones.
-- Accounts opened at zero need no entry: an empty sum is already 0.
INSERT INTO ledger_entries (id, account_id, operation_key, direction, amount, currency, balance_after, created_at)
SELECT gen_random_uuid(), a.id, 'opening', 'CREDIT', a.balance, a.currency, a.balance, a.created_at
FROM accounts a
WHERE a.balance > 0;
