-- Adds the id as a trailing column so the keyset pagination over
-- (created_at DESC, id DESC) resolves its tiebreak from the index rather than
-- from a post-fetch sort. The leading column is unchanged, so this index
-- serves every lookup the dropped one did.

DROP INDEX idx_notifications_recipient;
CREATE INDEX idx_notifications_recipient
    ON notifications (recipient_account_id, created_at DESC, id DESC);
