-- The statement and account-list reads are paginated with a keyset over
-- (created_at DESC, id DESC), so the indexes backing them need the id as a
-- trailing column. Without it the tiebreak among rows sharing a timestamp is
-- resolved by a sort after the fetch instead of by the index, which is the
-- one case where a keyset silently loses its "one seek at any depth"
-- property.
--
-- Both replacements keep their original leading columns, so every lookup the
-- dropped indexes served is served at least as well by the new ones.

DROP INDEX idx_ledger_entries_account_created;
CREATE INDEX idx_ledger_entries_account_created
    ON ledger_entries (account_id, created_at DESC, id DESC);

DROP INDEX idx_accounts_owner_id;
CREATE INDEX idx_accounts_owner_created
    ON accounts (owner_id, created_at DESC, id DESC);
