# Getting started

Get oriented without changing shared data or exposing local credentials.

## Prerequisites

- Java 21 and the project Gradle wrapper.
- Node.js 22.12 or newer and npm for the documentation build.
- Docker and Docker Compose for infrastructure integration tests.

The codebase contains a Spring Boot backend in `backend/`, a TanStack
frontend in `client/`, and documentation in `docs/`. The backend app has
one Spring Modulith module per Java package; the
<a href="/docs/source/backend-readme.md" download>backend README</a> maps them.

## Build the documentation

From the project root:

```sh
npm ci --prefix docs
npm --prefix docs run build
```

The backend Gradle resource build also builds the site; it requires those npm
dependencies to be installed first. The dev backend serves the bundled site at
`http://127.0.0.1:8080/docs/` when running with the default port. Its contents
are rebuilt into the application artifact; edits are not live in a running jar.
Documentation sources and downloadable references are explicitly allowlisted
in `docs/scripts/build.mjs`; add new reference files there when needed.

## Run focused backend tests

From `backend/`, using an installed Java 21:

```sh
./gradlew test
```

Integration tests use disposable Testcontainers and require Docker. The
<a href="/docs/source/backend-readme.md" download>backend setup notes</a> cover local JDK selection,
provisioning, configuration, and the existing dev stack. They include
operations that affect local services: inspect and obtain approval before
running them against a shared environment.

::: danger Shared environment
Development data is disposable, but the environment may be shared. Confirm the
target and obtain explicit approval before resetting or migrating a shared
database, rebuilding shared infrastructure, or regenerating credentials.
Pending Flyway migrations run automatically when the backend starts.
:::

## Frontend

The current frontend provides backend sign-in, table and bet views, U2Pick
betting (create, pick, call results, host points settlement) and settle-up
receipts, which update live over a WebSocket. Chat, social and other
prototype features are not exposed yet. Accounts must already be provisioned
in the dev backend. In dev, the lock worker locks U2Pick bets at their
deadline and the outbox relay publishes events for live updates; set
`PVPICKS_BET_LOCK_WORKER_ENABLED=false` or `PVPICKS_OUTBOX_RELAY_ENABLED=false`
to turn either off. The sports ticker needs a BallDontLie API key in
`PVPICKS_BALLDONTLIE_API_KEY`; without one it says live scores are
unavailable.

For an approved isolated dev backend listening on `127.0.0.1:8080`, configure
its `PVPICKS_DEV_ORIGIN` as `http://127.0.0.1:5173` before starting it. From
`client/`, run `npm run dev` and open `http://127.0.0.1:5173`. Vite proxies
same-origin `/api` requests to the backend and preserves the browser's Origin;
the browser obtains a CSRF token before login and logout. Use the exact host and
port shown, and do not expose this development proxy to a network. This proxy
is for Vite development only, not a production deployment configuration.
For an isolated backend on another port, set `PVPICKS_DEV_BACKEND_URL` to its
HTTP loopback origin when starting Vite.

Never start or reconfigure a backend against shared infrastructure without
approval: startup automatically applies pending migrations.

## Authentication for API development

The dev backend uses an HttpOnly session cookie, same-origin checks, and a CSRF
token. Read the backend setup notes for the exact flow. LAN and production
profiles remain intentionally gated.
