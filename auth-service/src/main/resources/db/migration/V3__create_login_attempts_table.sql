-- Failed login attempts, for throttling the front door.
--
-- Keyed by the address that was SUBMITTED, whether or not it belongs to a
-- real user. That is the whole point rather than an accident: if only
-- registered addresses were counted, the throttle would answer the question
-- InvalidCredentialsException is careful never to answer. "Five tries and
-- then 429" versus "401 forever" tells an attacker exactly which addresses
-- are worth attacking, and no amount of identical error bodies hides it.
--
-- There is deliberately no locked_until column. The lock is derived from
-- failed_count and last_failure_at at read time, so the delay elapses on its
-- own and nothing has to expire a lock or unlock anything on a schedule.
CREATE TABLE login_attempts (
    email           VARCHAR(254) PRIMARY KEY,
    failed_count    INT NOT NULL,
    last_failure_at TIMESTAMPTZ NOT NULL
);

-- Serves the cleanup job. Anyone can put rows in this table just by naming an
-- address, so it needs a way to forget them: the row is a decaying counter,
-- not a record worth keeping.
CREATE INDEX idx_login_attempts_last_failure ON login_attempts (last_failure_at);
