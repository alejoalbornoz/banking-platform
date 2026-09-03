# Banking platform (portfolio project)

A backend banking system built as a set of independent services (SOA),
demonstrating real-world challenges in that domain: optimistic-locking
concurrency control, idempotent operations, event-driven communication over
RabbitMQ, distributed tracing, and interface-driven, test-covered service
design.

## Stack

- Java 21, Spring Boot 3.3
- PostgreSQL (database-per-service), Flyway migrations
- RabbitMQ for async, event-driven communication between services
- Micrometer Tracing + Zipkin for distributed tracing
- JUnit 5 + Mockito for testing
- Docker Compose for local infrastructure

## Project layout conventions

- Interfaces are named `I<Name>` (`IAccountService`, `IAccountRepository`, `IAccountMapper`, `IAccountEventPublisher`); the implementation takes the plain name (`AccountService`, `AccountMapper`, `AccountEventPublisher`) and lives in the same package - no `impl` subpackage.
- Entities live in `model` (not `domain`).
- Each service owns its exceptions and DTOs under its own `exception`/`dto` packages. `common` is intentionally minimal: it only holds event payload contracts (`AccountCreatedEvent`, `TransferCompletedEvent`, `TransferFailedEvent`, `DomainEvent`), since those genuinely must be identical between a publisher and its consumers. Everything else is duplicated per service on purpose, to keep each service independently deployable and readable on its own.

## Services (roadmap)

| Service | Port | Status | Responsibility |
|---|---|---|---|
| `account-service` | 8081 | ✅ built | Accounts, balances. Source of truth for how much money exists. |
| `transaction-service` | 8082 | ✅ built | Transfers between accounts: idempotency keys, saga + compensation, outbox pattern |
| `notification-service` | 8083 | planned | Consumes events off RabbitMQ, sends async notifications |
| `auth-service` | 8084 | planned | User registration/login, JWT issuance |
| `api-gateway` | 8080 | planned | Single entry point, routing |

## Running locally

1. Start infrastructure:
   ```bash
   docker compose up -d
   ```
   This brings up:
   - Postgres on `5432` (databases `account_db`, `transaction_db`, `notification_db`, `auth_db` are pre-created)
   - RabbitMQ on `5672` (management UI at http://localhost:15672, user/pass `banking`/`banking`)
   - Zipkin on `9411` (UI at http://localhost:9411)

2. Build everything:
   ```bash
   mvn clean install
   ```

3. Run the services (each in its own terminal). Both run their Flyway
   migrations automatically on startup:
   ```bash
   mvn -pl account-service spring-boot:run
   ```
   ```bash
   mvn -pl transaction-service spring-boot:run
   ```

## Trying the API

### account-service (port 8081)

```bash
# Create an account
curl -X POST localhost:8081/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"ownerId":"11111111-1111-1111-1111-111111111111","openingBalance":100.00,"currency":"USD"}'

# Fetch it (replace {id} with the id returned above)
curl localhost:8081/api/v1/accounts/{id}

# Credit it. Idempotency-Key is required on anything that moves money.
curl -X POST localhost:8081/api/v1/accounts/{id}/credit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: deposit-001" \
  -d '{"amount":50.00}'

# Debit it
curl -X POST localhost:8081/api/v1/accounts/{id}/debit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: withdrawal-001" \
  -d '{"amount":30.00}'

# List an owner's accounts
curl "localhost:8081/api/v1/accounts?ownerId=11111111-1111-1111-1111-111111111111"

# Read the statement, with the balance recomputed from the entries
curl localhost:8081/api/v1/accounts/{id}/ledger
```

Run the credit command twice. The balance goes up **once** - the second call
finds the key already posted and replays the result. Reuse `deposit-001` with a
different amount and you get `409 OPERATION_KEY_REUSED`.

### transaction-service (port 8082)

Create two accounts first, then transfer between them. The `Idempotency-Key`
header is **required**:

```bash
curl -X POST localhost:8082/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 3f9a1c7e-0001-4a2b-9c3d-000000000001" \
  -d '{"sourceAccountId":"{sourceId}","destinationAccountId":"{destId}","amount":25.00,"currency":"USD"}'
```

Send that exact command a second time and the money moves **once**: the second
call replays the stored result instead of transferring again. Change the amount
but keep the same key and you get `409 IDEMPOTENCY_KEY_REUSED`.

```bash
# Look up a transfer's final state
curl localhost:8082/api/v1/transfers/{transactionId}
```

Traces for these requests show up in the Zipkin UI at http://localhost:9411 -
a transfer produces one trace spanning both services.

## How concurrency is handled (account-service)

`Account.version` is a JPA `@Version` field, so every `UPDATE` includes
`WHERE id = ? AND version = ?`. If two requests read the same account and
both try to commit a change, the second one's `UPDATE` affects zero rows and
Hibernate raises an optimistic-locking exception.

Rather than let that bubble up as a hard failure, `AccountService` wraps
every credit/debit in a `RetryTemplate` (see `RetryConfig`): each retry
attempt opens a **new** transaction via `TransactionTemplate` and re-reads
the account, so it's retrying against the current state, not blindly
resubmitting a stale write. After 3 failed attempts it gives up and surfaces
a `ConcurrentUpdateException` (HTTP 409) for the caller to retry at the
request level.

This is deliberately optimistic locking rather than pessimistic (`SELECT ...
FOR UPDATE`): most concurrent operations touch different accounts, so paying
the cost of a held row lock on every single write doesn't pay off.

## The ledger, and why credit/debit are idempotent

`accounts.balance` is a running total kept for fast reads. The truth behind it
is `ledger_entries`: one immutable, append-only posting per balance change,
written **in the same transaction** as the change itself. Correcting a mistake
means posting an opposing entry, never editing history.

Every credit and debit carries a caller-supplied `Idempotency-Key`, stored on
the entry as its `operation_key` with a `UNIQUE (account_id, operation_key)`
constraint. That constraint is doing the real work, and it's worth being
precise about which hazard it solves, because two very different ones look
similar:

- **Two *different* operations on one account** race on the `@Version` column.
  The loser is safe to redo, so it's retried in a fresh transaction against
  the winner's state. Both end up applied.
- **The *same* operation submitted twice** - a client retry, or a call that
  timed out after it had actually committed - must *not* be redone. Optimistic
  locking is no help here: both writes are individually valid, and the second
  would move money again. The unique constraint is what stops it.

The service looks the key up first, which covers the ordinary case where the
original call committed some time ago. For genuinely simultaneous duplicates -
both look, both find nothing, both insert - only the database can arbitrate,
so the loser catches the constraint violation and **replays the winner's
outcome instead of treating it as an error**. Same key with a *different*
amount is refused (`409 OPERATION_KEY_REUSED`): replaying it would silently do
something other than what the caller asked, and applying it would break the
promise the key makes.

The key is scoped per account rather than globally, because one transfer
legitimately posts to two accounts and those postings must not collide with
each other.

Because each entry also stores `balance_after`, the balance can be re-derived
and checked at any time. `GET /api/v1/accounts/{id}/ledger` does exactly that
and returns `reconciled`, so a broken invariant surfaces as a visible flag
rather than as quietly wrong money.

**This is what makes the transfer saga safe.** transaction-service derives a
stable key per leg from its own transaction id (`<txId>:debit`,
`<txId>:credit`, `<txId>:compensation`) rather than generating one per attempt
- a fresh UUID each time would make every retry look like a brand new
operation, which is precisely how double-spending happens. Because the key is
derived from durable state, even a retry from a different process after a
crash recomputes the identical key.

## How a transfer works (transaction-service)

A transfer has to debit one account and credit another, in a different
service, over HTTP. There is no distributed transaction to lean on, so it's
run as a **saga with one compensating action**, tracked by a row whose status
is the saga's state machine:

```
PENDING ──debit ok──> DEBITED ──credit ok──> COMPLETED
   │                     │
   │ debit failed        │ credit failed → credit source back
   ↓                     ↓                        │
 FAILED                FAILED <──────────ok───────┤
 (nothing moved)    (compensated)                 │
                                                  ↓ compensation also failed
                                        COMPENSATION_FAILED
                                        (money stuck - needs ops)
```

Three things make this safe:

**Idempotency.** Every transfer carries a client-supplied `Idempotency-Key`
with a unique constraint on the column. A retry with the same key finds the
existing row and replays its result instead of moving money twice. If two
concurrent requests race to insert the same key, the loser catches the
constraint violation and reads back the winner's row rather than failing.
A key reused with *different* request contents is rejected outright (409) -
it's ambiguous which transfer the client wants, so guessing would be worse
than refusing.

**Crash recovery.** Because the status is persisted between steps, a retry of
a transfer that died mid-saga resumes rather than restarts: a `DEBITED` row
means the debit already committed, so the retry picks up at the credit step
instead of debiting a second time.

**No open transaction across a network call.** The saga method is deliberately
*not* one big `@Transactional`. Each database write is its own short
transaction via `TransactionTemplate`, with the HTTP calls happening in
between - holding a DB connection (and possibly row locks) open for the
duration of another service's call, retries included, is how connection pools
die.

## Reliable events: the outbox pattern (transaction-service)

Committing to the database and publishing to RabbitMQ are two separate
systems; crash between them and they disagree forever. account-service
currently publishes `AccountCreatedEvent` after commit via a transaction
synchronization, which closes the rollback hole but not the crash hole -
acceptable for a welcome notification.

transaction-service can't accept that, so it uses the outbox pattern:

1. `TransferService` writes the event as a row in `outbox_events` **in the
   same local transaction** as the status change that caused it. Same
   database, so it's genuinely atomic - the event and the state can never
   disagree.
2. `OutboxRelay` polls for unpublished rows and publishes them to RabbitMQ,
   marking each published in its own transaction. If the broker is down, the
   transaction rolls back, the row stays unpublished, and the next poll tries
   again. A broker outage delays events; it never loses them.

The trade-off is at-least-once delivery: the relay can crash after publishing
but before marking the row, so consumers must deduplicate on `eventId`. That
is why `DomainEvent` carries one.

Polling is the simple implementation, which is the right call at this scale.
Production systems at high throughput usually switch to change-data-capture
(Debezium tailing the WAL) to avoid constantly `SELECT`ing the table.

## Known gaps

Being explicit about what is *not* solved yet, since these are the interesting
parts:

- **No integration tests yet.** Current tests are unit tests with Mockito.
  Testcontainers (real Postgres + RabbitMQ) would cover the parts mocks can't:
  the actual unique-constraint races under real concurrency, the Flyway
  migrations, genuine optimistic-lock failures.
- **Currency isn't validated across a transfer.** A transfer carries a
  currency, but nothing checks it against the two accounts' currencies, so a
  USD transfer between EUR accounts would go through. Doing this properly
  means deciding what multi-currency even means here (reject the mismatch, or
  introduce FX), which is its own piece of work.
- **`transfer.failed` events aren't consumed by anything yet**, so a
  `COMPENSATION_FAILED` transfer sits in the database instead of paging
  someone.
- **No auth.** Every endpoint is open, and `ownerId` is trusted from the
  request body.

## Next steps

- `notification-service` consuming `account.created` / `transfer.*` off RabbitMQ
- `auth-service` + API gateway
- Testcontainers-based integration tests
