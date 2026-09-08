-- Refresh tokens, with rotation and reuse detection.
--
-- Three columns carry the whole mechanism:
--
--   token_hash  VARCHAR, not CHAR(64): Postgres bpchar pads with spaces and
--               saves nothing, and the entity maps a String to varchar - a
--               mismatch ddl-auto: validate refuses to start on.
--               Only the SHA-256 of the token is stored, never the token
--               itself - a dump of this table must not hand anyone a working
--               credential. SHA-256 rather than bcrypt on purpose: bcrypt's
--               work factor exists to slow brute force against guessable
--               human passwords, and there is nothing to guess in 256 bits of
--               SecureRandom. It would also make the lookup below impossible,
--               since a salted hash can't be looked up - only compared, one
--               row at a time, against every row in the table.
--
--   family_id   Every token descended from one login shares it. Rotation
--               issues a new token in the same family, so revoking the family
--               revokes the whole chain at once.
--
--   used_at     Set exactly once, when a token is exchanged. A token arriving
--               with this already set is a token being used twice, which
--               cannot happen legitimately - the client replaced it the first
--               time. See AuthService.refresh.
CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    user_id    UUID NOT NULL REFERENCES users (id),
    family_id  UUID NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

-- Revoking a compromised family touches every row in it.
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- Serves the cleanup job, which deletes long-expired rows so this table
-- doesn't grow for the lifetime of the deployment.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
