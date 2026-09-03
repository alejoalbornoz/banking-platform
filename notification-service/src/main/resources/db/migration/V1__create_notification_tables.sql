-- One row per event this service has ever handled, keyed by the event's own
-- id (never generated here). Redelivering the same message tries to insert
-- the same event_id twice, hits this primary key, and is treated as a no-op
-- rather than a repeated notification.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Separate from processed_events on purpose: one event can produce more than
-- one notification (a completed transfer notifies both the sender and the
-- receiver), but it is only ever processed once.
CREATE TABLE notifications (
    id                   UUID PRIMARY KEY,
    event_id             UUID NOT NULL REFERENCES processed_events (event_id),
    recipient_account_id UUID NOT NULL,
    type                 VARCHAR(20) NOT NULL,
    message              VARCHAR(500) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_notifications_type CHECK (
        type IN ('ACCOUNT_CREATED', 'TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_FAILED')
    )
);

-- Serves the "my notifications" read: one account's notifications, newest first.
CREATE INDEX idx_notifications_recipient ON notifications (recipient_account_id, created_at DESC);
