-- A second projection over the same events, and what that forces on the
-- first.
--
-- Until now processed_events answered "has this event been handled", full
-- stop. With two projections that question has no single answer: an event
-- can be handled by the notifications projection and not yet by movements,
-- and a replayed event must be skipped by the one that saw it and taken by
-- the one that did not. Each projection keeps its own cursor, which is the
-- CQRS fundamental - so the key becomes (event_id, projection), and every
-- existing row is attributed to the only projection that existed when it
-- was written.
-- notifications.event_id referenced processed_events(event_id), which is
-- about to stop being unique on its own - and Postgres will not let the key
-- go while something depends on it, so this has to come first. The invariant
-- the FK expressed (a notification's event was processed) still holds, but
-- by construction now: both rows are written in one transaction by the
-- notifications projection. A composite FK would need notifications to carry
-- the projection name, a column that would exist only to satisfy a
-- constraint.
ALTER TABLE notifications DROP CONSTRAINT notifications_event_id_fkey;

ALTER TABLE processed_events ADD COLUMN projection VARCHAR(32) NOT NULL DEFAULT 'notifications';
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, projection);
ALTER TABLE processed_events ALTER COLUMN projection DROP DEFAULT;


-- Which account belongs to whom, projected from account.created. This is
-- what lets "all my movements" be answered without a network call: the
-- ownership every other read in this service has to ask account-service
-- for is, for this one, already here.
CREATE TABLE account_owners (
    account_id   UUID PRIMARY KEY,
    owner_id     UUID NOT NULL,
    currency     VARCHAR(3) NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Money that actually moved, one row per account it touched. A completed
-- transfer produces two: SENT on the source, RECEIVED on the destination.
-- Failed transfers produce none - nothing moved, or it moved and was undone,
-- and that is a notification, not a movement.
--
-- owner_id is nullable because the events that produce a movement can arrive
-- before the account.created event that says who owns the account. The
-- projector fills it in when that event turns up; until then the row exists
-- but is not yet anyone's. See MovementProjector.
CREATE TABLE movements (
    id                      UUID PRIMARY KEY,
    event_id                UUID NOT NULL,
    account_id              UUID NOT NULL,
    owner_id                UUID,
    kind                    VARCHAR(16) NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    counterparty_account_id UUID,
    transaction_id          UUID,
    occurred_at             TIMESTAMPTZ NOT NULL,

    -- One event touches an account at most once. This is the row-level
    -- backstop behind the projection's own idempotency.
    CONSTRAINT uq_movements_event_account UNIQUE (event_id, account_id),
    CONSTRAINT chk_movements_kind CHECK (kind IN ('OPENING', 'SENT', 'RECEIVED'))
);

-- Serves the read: one owner's movements, newest first, keyset on (occurred_at, id).
CREATE INDEX idx_movements_owner_occurred ON movements (owner_id, occurred_at DESC, id DESC);

-- Serves the late-owner backfill: rows still waiting to learn who they belong to.
CREATE INDEX idx_movements_unowned ON movements (account_id) WHERE owner_id IS NULL;
