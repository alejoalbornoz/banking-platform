-- Single-use, short-lived tokens for the forgot-password flow.
--
-- Same shape as refresh_tokens, and for the same reasons: only the SHA-256 is
-- stored (a dump of this table must not be a set of working account
-- takeovers), the digest is unsalted so the presented token can be looked up
-- in one indexed hit, and used_at is set exactly once by a conditional UPDATE
-- rather than by reading the row and deciding in Java.
--
-- No `dead`/attempts here, unlike the outbox: there is nothing to retry. A
-- token is either spendable or it is not.
CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    user_id    UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,

    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash)
);

-- Issuing a new token invalidates whatever the same user still has
-- outstanding, so several live tokens never float around at once.
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);

-- Serves the cleanup job.
CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
