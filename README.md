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
- Each service owns its exceptions and DTOs under its own `exception`/`dto` packages. `common` is intentionally minimal: it only holds event payload contracts (`AccountCreatedEvent`, `DomainEvent`), since those genuinely must be identical between a publisher and its consumers. Everything else is duplicated per service on purpose, to keep each service independently deployable and readable on its own.

## Services (roadmap)

| Service | Status | Responsibility |
|---|---|---|
| `account-service` | ✅ scaffolded | Accounts, balances, holds. Source of truth for how much money exists. |
| `transaction-service` | planned | Transfers, deposits, withdrawals, ledger, idempotency, outbox pattern |
| `notification-service` | planned | Consumes events off RabbitMQ, sends async notifications |
| `auth-service` | planned | User registration/login, JWT issuance |
| `api-gateway` | planned | Single entry point, routing |

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

3. Run the account service:
   ```bash
   cd account-service
   mvn spring-boot:run
   ```
   It starts on `8081` and runs its Flyway migrations automatically on startup.

## Trying the account-service API

```bash
# Create an account
curl -X POST localhost:8081/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"ownerId":"11111111-1111-1111-1111-111111111111","openingBalance":100.00,"currency":"USD"}'

# Fetch it (replace {id} with the id returned above)
curl localhost:8081/api/v1/accounts/{id}

# Credit it
curl -X POST localhost:8081/api/v1/accounts/{id}/credit \
  -H "Content-Type: application/json" \
  -d '{"amount":50.00}'

# Debit it
curl -X POST localhost:8081/api/v1/accounts/{id}/debit \
  -H "Content-Type: application/json" \
  -d '{"amount":30.00}'

# List an owner's accounts
curl "localhost:8081/api/v1/accounts?ownerId=11111111-1111-1111-1111-111111111111"
```

Traces for these requests show up in the Zipkin UI at http://localhost:9411.

## How concurrency is handled (account-service)

`Account.version` is a JPA `@Version` field, so every `UPDATE` includes
`WHERE id = ? AND version = ?`. If two requests read the same account and
both try to commit a change, the second one's `UPDATE` affects zero rows and
Hibernate raises an optimistic-locking exception.

Rather than let that bubble up as a hard failure, `AccountServiceImpl` wraps
every credit/debit in a `RetryTemplate` (see `RetryConfig`): each retry
attempt opens a **new** transaction via `TransactionTemplate` and re-reads
the account, so it's retrying against the current state, not blindly
resubmitting a stale write. After 3 failed attempts it gives up and surfaces
a `ConcurrentUpdateException` (HTTP 409) for the caller to retry at the
request level.

This is deliberately optimistic locking rather than pessimistic (`SELECT ...
FOR UPDATE`): most concurrent operations touch different accounts, so paying
the cost of a held row lock on every single write doesn't pay off. When we
build the Transactions service, we'll hit a related but harder problem — a
transfer debits one account and credits another, potentially owned by
different aggregates or services — which is where the outbox pattern and,
if it crosses service boundaries, a saga come in.

## Next steps

- `transaction-service`: transfers between accounts, idempotency keys, the
  outbox pattern for reliably publishing `TransferCompleted` events
- Wire `account-service` to publish `AccountCreatedEvent` (already defined in
  `common`) onto RabbitMQ
- `notification-service` consuming those events
- `auth-service` + API gateway
- Testcontainers-based integration tests (real Postgres/RabbitMQ instead of mocks)
