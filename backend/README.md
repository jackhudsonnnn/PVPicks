# Backend

A development-only Spring Boot backend for P2Picks. It is **not** ready for real
users, LAN or production: do not expose it to a network. The `lan` and `prod`
profiles are deliberately rejected; the application starts only with `dev` and
binds to `127.0.0.1`.

What is implemented and what is planned:

- [Project status](../docs/site/status.md): available features and gaps.
- [Backend contract inventory](../docs/reference/backend-contract-inventory.md):
  per-endpoint behavior, retry protection and known limits.
- [Domain rules](../docs/reference/domain-rules.md) and
  [architecture](../docs/site/architecture.md): invariants, lock order and boundaries.
- `api/openapi.yaml` and `api/realtime.schema.json`: draft contracts that
  include planned operations, not an implementation inventory.

## Code layout

```text
backend/
  app/                    Spring Boot application: one Modulith module per package
    src/main/java/com/p2picks/app/
      P2PicksApplication.java
      users/              profile, user search and lookup
      social/             follows/blocks, invitations, invite preferences
      tables/             create, profile, settings, members, hosts, permissions,
                          remainder, Quick Pick controls, archives, reads
      chat/               message windows, sending, reactions, read state
      bets/               U2Pick proposal/pick/lock/resolution, payout and settler
                          rules, bet reads, lock worker
      settlement/         points settlement, receipts, reviews, debt and debt cap,
                          settlement and debt calculators
      feedback/  admin/   feedback submission; admin health
      config/             dev-only profile guard, Flyway, developer docs
      common/  security/  shared infrastructure (open modules): IdempotencyGuard,
                          Outbox/OutboxEvent, ApiErrors, PostgresText,
                          BettingLifecycleReady; sessions, filters,
                          MutationAuthentication
    src/main/resources/   application config and Flyway migrations (db/migration)
    src/test/java/        tests mirror the main packages; IntegrationTestSupport
                          holds the shared containers, Spring context and helpers
  shared/kernel/          Money: exact integer-cent value type (plain Java library)
  api/                    draft OpenAPI and realtime contracts
  dev/                    dev seed SQL
```

### Module rules

Each package's `package-info.java` marks it as a Spring Modulith module and
states what it covers. Feature modules also list their `allowedDependencies`;
for example `tables` may use `chat`, `settlement`, `common` and `security`.

- A module's **public types are its API**. Everything else stays package-private,
  so a class becomes visible to other modules only by being made public.
- Feature modules depend only on the modules they declare. The graph must stay
  acyclic, and `common` and `security` must not depend on feature modules.
- Record events with `common.Outbox` and an `OutboxEvent` constant, and add chat
  messages from other modules with `chat.ChatMessageWriter`. A test fails if any
  other class writes the `outbox` or `chat_messages` tables directly.
- The payout, U2Pick settler, debt and table-settlement calculators are pure
  Java: no Spring, JDBC or other modules.
- `ModuleArchitectureTest` enforces all of this; `MODULES.verify()` names the
  offending class when a rule is broken. When adding a module, create its
  package with a `package-info.java` and add it to the test's module set.
- The database is not yet divided along module lines: many queries read or lock
  other modules' tables inside one transaction. The
  [architecture guide](../docs/site/architecture.md#modules-and-data-ownership)
  maps which module owns each table.

Controllers map service outcomes to HTTP statuses and use `ApiErrors.error` for
`{code, message}` error bodies. Mutations that support retries call
`MutationAuthentication` and `IdempotencyGuard` inside their transaction.

Proposal, pick/clear, U2Pick result, points-settlement and receipt HTTP adapters
are conditional on `BettingLifecycleReady`, which has no application bean. Tests
register them through `GatedBetAdapters` and `GatedSettlementAdapters`. Do not
register the marker until the complete betting and receipt workflow is safe.

## Local setup

Requires Java 21, Docker with Compose, `openssl` and Node.js 22.12+ (for the
docs build). On this machine the approved project-local JDK can be selected
from `backend/`:

```sh
export JAVA_HOME="$PWD/.tools/jdk-21/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.tools/gradle"
```

Install the documentation dependencies once from the project root with
`npm ci --prefix docs`. Gradle builds the docs into the application resources;
pass `-PdocsPrebuilt=true` to reuse an existing `docs/site/.vitepress/dist`.

### Run with Compose

From the project root:

```sh
./scripts/provision-dev.sh --init
docker compose -f infra/compose/docker-compose.dev.yml up --build
```

After the backend has started and applied migrations, in another terminal:

```sh
./scripts/provision-dev.sh --provision
bash scripts/seed-dev.sh --seed
```

- `--init` generates the session signing key (`infra/compose/.env.auth`), the
  retry-fingerprint key (`infra/compose/.env.idempotency`) and four dev account
  passwords (`infra/compose/.env.dev-accounts`). These files are ignored and
  owner-only: never share or commit them. Lost passwords cannot be recovered
  from their BCrypt hashes.
- `--provision` writes the password hashes to PostgreSQL. Re-running verifies
  existing accounts rather than overwriting them.
- Keep the retry key with its database. Replacing a used key makes protected
  mutations fail closed and invalidates chat cursors; rotation and recovery are
  not implemented.
- The seed creates two sample tables and two active U2Pick bets for the
  intermediate and pro accounts, once, in one transaction. Never run it against
  LAN, production or real-user data.
- Compose requires `PVPICKS_DB_PASSWORD` in the ignored `infra/compose/.env`
  or the shell. `PVPICKS_PORT` (default 8080) changes the loopback host port.
- Add `--profile tools` before `up` for Kafka UI on `PVPICKS_KAFKA_UI_PORT`
  (default 8081). It is unauthenticated: use it only with disposable data.
- `down -v` deletes the PostgreSQL and Kafka volumes.

### Run outside Compose

Run `./gradlew :app:bootRun --args='--spring.profiles.active=dev'` with
`PVPICKS_DEV_JWT_SECRET` (from `.env.auth`), `PVPICKS_DEV_IDEMPOTENCY_SECRET`
(from `.env.idempotency`), `PVPICKS_DATASOURCE_URL`, `PVPICKS_DB_USER`,
`PVPICKS_DB_PASSWORD`, `PVPICKS_REDIS_HOST` and
`PVPICKS_KAFKA_BOOTSTRAP_SERVERS`. Compose does not publish the service ports.
For a browser origin other than `http://127.0.0.1:8080`, set
`PVPICKS_DEV_ORIGIN` to the exact loopback origin (the Vite client uses
`http://127.0.0.1:5173`).

The browser flow is: `GET /auth/csrf`, then `POST /auth/login` with the token in
`X-CSRF-TOKEN` and a same-origin `Origin`; fetch a new token after login and
logout. Sessions are HttpOnly, SameSite=Strict cookies that expire after eight
hours. They are not `Secure` because dev uses HTTP. TLS, secure cookies, rate
limiting and refresh tokens are required before LAN.

The handbook is served unauthenticated at `http://127.0.0.1:8080/docs/`.

## Safety

- Startup applies pending Flyway migrations. Do not start an updated backend
  against a shared database without explicit approval. Flyway does not baseline
  an existing nonempty schema: back up and inspect it first.
- The database role needs permission to install the `citext` and `pgcrypto`
  extensions.
- The U2Pick lock worker is off by default. `PVPICKS_BET_LOCK_WORKER_ENABLED=true`
  makes it scan immediately and then five seconds after each scan, locking
  overdue U2Pick bets in the connected database. Sports bets are not
  auto-locked. Do not enable it against shared data without approval.
- Privileged database operators bypass application rules (including the V24
  role guard) and can read plaintext picks.

## Database notes for new writers

The schema does not enforce every invariant; transactional writers must:

- acquire affected user rows in UUID order before table, bet, ledger or receipt
  locks when reading or changing debt;
- preserve host continuity and invitation eligibility (host, follow, block and
  preference rules) when adding members or invitations;
- normalize usernames or cast parameters to `citext`, because JDBC binds
  strings as `varchar`;
- write ledger entries as append-only, zero-sum rows per bet;
- write internal outbox rows in the same transaction. There is no relay,
  consumer or delivery yet.

## Tests

From `backend/`:

```sh
./gradlew test                 # all projects
./gradlew :app:test            # application only
```

Application integration tests share one Spring context and start PostgreSQL,
Redis and Kafka Testcontainers once per run, so Docker is required. They never
use the dev database. See the [test guide](../docs/reference/backend-test-map.md)
for what each suite covers and how to run the opt-in frontend preview tests.
