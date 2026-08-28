CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    account_number VARCHAR(34) NOT NULL,
    owner_id       UUID NOT NULL,
    balance        NUMERIC(19, 2) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);
