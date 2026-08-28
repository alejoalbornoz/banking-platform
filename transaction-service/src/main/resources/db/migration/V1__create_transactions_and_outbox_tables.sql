CREATE TABLE transactions (
    id                       UUID PRIMARY KEY,
    idempotency_key          VARCHAR(255) NOT NULL,
    source_account_id        UUID NOT NULL,
    destination_account_id   UUID NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    status                   VARCHAR(24) NOT NULL,
    failure_reason           VARCHAR(500),
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_status CHECK (
        status IN ('PENDING', 'DEBITED', 'COMPLETED', 'FAILED', 'COMPENSATION_FAILED')
    ),
    CONSTRAINT chk_transactions_accounts_differ CHECK (source_account_id <> destination_account_id)
);

CREATE INDEX idx_transactions_source_account ON transactions (source_account_id);
CREATE INDEX idx_transactions_destination_account ON transactions (destination_account_id);

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT NOT NULL,
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The relay polls exactly this shape: unpublished rows, oldest first.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published = FALSE;
