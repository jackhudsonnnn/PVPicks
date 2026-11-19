# Backend

A Spring Boot backend for P2Picks. The `dev` profile is for local
development: it binds to `127.0.0.1`, and you must not expose it to a
network. The `lan` profile runs the [LAN watch party](/lan) behind Caddy
over HTTPS. Production isn't configured, and any other profile refuses to
start.

What is implemented and what is planned:

- [Project status](/status): available features and gaps.
- [API behavior notes](/reference/api-notes):
  per-endpoint behavior, retry protection and known limits.
- [Domain rules](/reference/domain-rules) and
  [architecture](/architecture): invariants, lock order and boundaries.
- `backend/api/openapi.yaml` (<a href="/docs/source/openapi.yaml" download>download</a>) and
  `backend/api/realtime.schema.json`: draft contracts that
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
                          remainder, Quick Pick controls, archives, reads, tomatoes
      chat/               message windows, sending, reactions, read state
      bets/               U2Pick proposal/pick/lock/resolution, payout and settler
                          rules, bet reads (per table and GET /me/bets), table
                          balances, lock worker
      settlement/         points settlement, receipts, reviews, debt and debt cap,
                          settlement and debt calculators
      sports/             sports feed (BallDontLie), game history, players and
                          player stats, GET /ticker, the admin sports explorer
      delivery/           outbox relay to Kafka and cleanup, realtime consumer and
                          the STOMP WebSocket gateway (/ws) with its audience checks
      feedback/           feedback submission and the admin inbox
      admin/              player audits and the audit log, request timings
                          (/perf, /admin/perf), health and runtime gauges
      moderation/         reports (POST /reports) and the admin moderation queue
      accounts/           freeze/unfreeze and account deletion across modules
      devdata/            dev sports data: scenario files and recorded chunks
      demo/               the dev demo world: users, history, games, bots, reset
      config/             dev-only profile guard, Flyway, developer docs
      common/  security/  shared infrastructure (open modules): IdempotencyGuard,
                          Outbox/OutboxEvent, ApiErrors, PostgresText;
                          sessions, filters, MutationAuthentication
    src/main/resources/   application config and Flyway migrations (db/migration)
    src/test/java/        tests mirror the main packages; IntegrationTestSupport
                          holds the shared containers, Spring context and helpers
  shared/kernel/          Money: exact integer-cent value type (plain Java library)
  api/                    draft OpenAPI and realtime contracts
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
  [architecture guide](/architecture#modules-and-data-ownership)
  maps which module owns each table.

Controllers map service outcomes to HTTP statuses and use `ApiErrors.error` for
`{code, message}` error bodies. Mutations that support retries call
`MutationAuthentication` and `IdempotencyGuard` inside their transaction.

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

The root `README.md` has the quick start. From the project root,
`docker compose up -d --build` starts everything in `compose.yaml` (which
includes `infra/compose/docker-compose.dev.yml`): PostgreSQL, Redis, Kafka, the
backend (serving these docs at `/docs/`) and the client. Run
`./scripts/provision-dev.sh --init` once first.

- `--init` creates `infra/compose/.env` from `infra/compose/.env.example`
  (every setting the stack reads, with defaults) with a random database
  password, plus the session signing key (`infra/compose/.env.auth`) and the
  retry-fingerprint key (`infra/compose/.env.idempotency`). It keeps existing
  files. They're git-ignored and owner-only: never share or commit them.
- There are no dev passwords. An empty database gets the demo world on
  startup (about 200 demo users plus newbie, intermediate, pro and admin), and
  the sign-in page signs you in as anyone you pick. **Reset data** on the Dev
  page wipes app data (sports data stays) and reseeds it. See
  [Demo world](/demo-data). The demo exists only in the dev profile (`pvpicks.demo.enabled`
  in `application-dev.yml`; tests turn it off). Demo users are played by bots
  (`demo/DemoBots`) through the real services; `pvpicks.demo.bots.enabled=false`
  turns them off. Players have no password (they sign in with codes); the demo
  admin's password is `demo`.
- Keep the retry key with its database. Replacing a used key makes protected
  mutations fail closed and invalidates chat cursors; rotation and recovery are
  not implemented.
- Compose requires `PVPICKS_DB_PASSWORD` in `infra/compose/.env` or the
  shell. `PVPICKS_PORT` (default 8080) changes the backend's loopback
  host port, and `PVPICKS_CLIENT_PORT` (default 5173) the client's. The backend
  accepts browser calls from `PVPICKS_DEV_ORIGIN` (default the client's
  address).
- PostgreSQL is published on loopback at `PVPICKS_DB_PORT` (default 5433) for a
  local client such as pgAdmin: database and user `p2picks`, password
  `PVPICKS_DB_PASSWORD`.
- Add `--profile tools` before `up` for Kafka UI on `PVPICKS_KAFKA_UI_PORT`
  (default 8081). It is unauthenticated: use it only with disposable data.
- `docker compose logs -f backend` follows the backend's log; `down` stops
  everything and `down -v` also deletes the PostgreSQL and Kafka volumes.

### Reset

- **App data:** **Reset data** on the Dev page wipes users, social, tables,
  bets, chat and settlements, keeps sports data (games, players, stats), and
  reseeds the demo world. Everyone is signed out.
- **Everything:** from the project root, `docker compose down -v`, then
  `docker compose up -d --build --wait`. Startup migrates the empty database and seeds the
  demo world. The next poll restores yesterday's to tomorrow's games, but
  finished games' player stats are gone. The local key files, `infra/compose/.env`,
  `recordings/` and `dev/sports/` are untouched; there's nothing to
  re-provision.

### Run outside Compose

Run `./gradlew :app:bootRun --args='--spring.profiles.active=dev'` with
`PVPICKS_DEV_JWT_SECRET` (from `.env.auth`), `PVPICKS_DEV_IDEMPOTENCY_SECRET`
(from `.env.idempotency`), `PVPICKS_DATASOURCE_URL`, `PVPICKS_DB_USER`,
`PVPICKS_DB_PASSWORD`, `PVPICKS_REDIS_HOST` and
`PVPICKS_KAFKA_BOOTSTRAP_SERVERS`. Compose does not publish the service ports.
For a browser origin other than `http://127.0.0.1:8080`, set
`PVPICKS_DEV_ORIGIN` to the exact loopback origin (the Vite client uses
`http://127.0.0.1:5173`).

The browser flow is: `GET /auth/csrf`, then `POST /auth/code` (players) or `POST
/auth/login` (admins) with the token in `X-CSRF-TOKEN` and a same-origin
`Origin`; fetch a new token after sign-in and sign-out. A stale or revoked
session cookie never blocks these ways in (`security.JwtCookieFilter`), so a
player whose code was rotated can sign straight back in with the new one.
Sessions are HttpOnly, SameSite=Strict cookies that expire after eight hours.
They are `Secure` in the `lan` profile and not in dev, which uses HTTP. Failed
password sign-ins are
throttled in Redis: by default 10 per account name or 50 per client address in
15 minutes (`PVPICKS_LOGIN_THROTTLE_PER_ACCOUNT`,
`PVPICKS_LOGIN_THROTTLE_PER_ADDRESS`, `PVPICKS_LOGIN_THROTTLE_WINDOW`). After
that, attempts get 429 until the window ends. Wrong sign-in codes have their own
budget ([Kafka & Redis](/kafka-redis)). If Redis is down the throttle allows
every attempt. Refresh tokens are still to be designed (production).

The handbook is served unauthenticated at `http://127.0.0.1:8080/docs/`.

### Run the LAN stack

The [LAN watch party](/lan) page has the full runbook. In short, from the
repository root: `./scripts/provision-lan.sh --init`, fill in
`infra/compose/.env.lan`, then
`docker compose -f infra/compose/docker-compose.lan.yml --env-file infra/compose/.env.lan up -d --build --wait`.
It is a separate Compose project (`pvpicks-lan`) with its own volumes and
keys. To try it on this Mac before the domain exists, set
`PVPICKS_LAN_HOST=localhost` and `PVPICKS_LAN_TLS=internal`.

## Safety

- Startup applies pending Flyway migrations. The dev environment is local and
  disposable (see the root `AGENTS.md`), so resetting it is fine; any future shared or
  production database needs backups and approval first. Flyway does not
  baseline an existing nonempty schema.
- The database role needs permission to install the `citext` and `pgcrypto`
  extensions.
- The U2Pick lock worker is on by default in the dev profile: it scans at
  startup and then five seconds after each scan, locking overdue U2Pick bets
  in the connected database. Sports bets are not auto-locked.
  `PVPICKS_BET_LOCK_WORKER_ENABLED=false` turns it off.
- The outbox relay is also on by default in dev: it publishes unpublished
  outbox rows to Kafka and sets their `published_at`;
  `PVPICKS_OUTBOX_RELAY_ENABLED=false` turns it off. Realtime delivery needs Kafka; without it the
  app still runs and clients fall back to refreshing on demand. A daily job
  also deletes published outbox rows older than `PVPICKS_OUTBOX_RETENTION`
  (default `P7D`); `PVPICKS_OUTBOX_CLEANUP_ENABLED=false` turns it off.
- The sports feed calls BallDontLie only when `PVPICKS_BALLDONTLIE_API_KEY` is
  set (keep it in the ignored `infra/compose/.env` or the shell, never in
  source). It polls every `PVPICKS_SPORTS_POLL_INTERVAL` (default `PT60S`, one
  request per league); keep that within your plan's rate limit. With
  `PVPICKS_SPORTS_RECORDING_DIR` set (Compose sets it), every raw response is
  saved as its own file in the git-ignored `recordings/sports/`, as
  `<league>/<New York date>/<UTC timestamp>_<endpoint>.json`. Names sort in
  receive order, so replay can read them in name order.
- With a key set, player data is polled too, one request every
  `PVPICKS_SPORTS_STATS_INTERVAL` (default `PT20S`): player stats for the live
  game that has waited longest, finished games every 15 minutes for six hours
  after they start (to catch corrections), and otherwise the next page of a
  league's active-player list, refreshed every
  `PVPICKS_SPORTS_PLAYERS_INTERVAL` (default `PT10M`; every fourth request
  while games are live, so it always finishes). A rate-limited response
  pauses this for a minute. Scores and player data share the plan's request
  budget (the GOAT trial allows 5 a minute: 2 for scores, 3 for player data).
- `GET /ticker` is cached in Redis. A poll that changes a game invalidates
  it after commit; otherwise entries live `PVPICKS_TICKER_CACHE_TTL` (default
  `PT5S`). Code or scripts that write `games` without `GameSync` must call
  `TickerCache.invalidate()`. If Redis is down, it reads PostgreSQL.
- Dev sports data (dev profile): with `PVPICKS_DEV_SPORTS_DIR` set (Compose
  mounts `dev/sports` there), the `devdata` module plays scenario files and
  recorded chunks as `DEV-` games through the same game and stats sync, and the
  app shows a Dev page to control them. `PVPICKS_DEV_SPORTS_TICK` sets how often
  (default `PT2S`). The stats poller skips dev games. See
  [Dev sports data](/dev-sports-data); cut chunks with `scripts/cut-sports-chunk.py`.
- Dated copies of the provider's OpenAPI specs live in `api/providers/`;
  `ProviderContractTest` checks trimmed real responses against them. When it
  fails, re-download the spec and re-check the mapping in `BallDontLieFeed`.
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
- write outbox rows through `common.Outbox` in the same transaction. The relay
  delivers at least once and not necessarily in commit order, so consumers
  must tolerate duplicates and reordering. Never put picks, message content or
  other private data in a payload that a realtime invalidation could expose;
  invalidations are built in `delivery.RealtimeConsumer`.

## Tests

From `backend/`:

```sh
./gradlew test                 # all projects
./gradlew :app:test            # application only
```

Application integration tests share one Spring context and start PostgreSQL,
Redis and Kafka Testcontainers once per run, so Docker is required. They never
use the dev database. See the [test guide](/reference/test-guide)
for what each suite covers and how to run the opt-in frontend integration tests.
