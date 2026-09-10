-- Gives the relay somewhere to record that a row is not merely unpublished
-- yet, but failing.
--
-- Until now the only states were published and not-published, so an event
-- that could never be published was indistinguishable from one that had just
-- been written a moment ago. It was retried on every poll forever, and
-- because the poll is ordered oldest-first, it was retried FIRST - blocking
-- every event behind it.
--
--   attempts    how many times publishing this specific row has been tried
--               and failed. Only counts failures that were about this
--               message; a broker outage is not the row's fault and does not
--               advance it. See OutboxRelay.
--   last_error  what went wrong most recently, so the dead-letter can be
--               diagnosed without reproducing it.
--   dead_at     set once attempts run out. A nullable timestamp rather than a
--               boolean, matching published_at / revoked_at / used_at
--               elsewhere in this project: it records when, not just whether.
ALTER TABLE outbox_events ADD COLUMN attempts   INT NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD COLUMN last_error VARCHAR(500);
ALTER TABLE outbox_events ADD COLUMN dead_at    TIMESTAMPTZ;

-- The old index still matched dead rows, which would have kept them at the
-- head of every poll - the exact problem this migration exists to end. The
-- replacement narrows the partial index to rows the relay can still act on.
DROP INDEX idx_outbox_events_unpublished;
CREATE INDEX idx_outbox_events_publishable
    ON outbox_events (created_at)
    WHERE published = FALSE AND dead_at IS NULL;
