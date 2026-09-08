-- Who asked for this transfer. Until now a transaction row recorded only the
-- two account ids, which is enough to authorize a single lookup (ask
-- account-service who owns them) but not to list a user's own transfers:
-- answering that per row would mean one network call per row.
--
-- Nullable, and not backfilled, because it genuinely cannot be: this service
-- never recorded who initiated the transfers that already exist, and inventing
-- a value would be worse than admitting the gap. Rows predating this migration
-- simply do not appear in anyone's history; they remain readable by id.
ALTER TABLE transactions ADD COLUMN initiated_by UUID;

-- Serves the history read: one user's transfers, newest first, with the id as
-- the keyset tiebreak.
CREATE INDEX idx_transactions_initiated_by
    ON transactions (initiated_by, created_at DESC, id DESC);
