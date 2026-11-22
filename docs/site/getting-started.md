# Getting started

Get oriented, run the stack locally and keep credentials out of source control.

## Prerequisites

- Java 21 and the project Gradle wrapper.
- Node.js 22.12 or newer and npm for the documentation build.
- Docker and Docker Compose for infrastructure integration tests.

The codebase contains a Spring Boot backend in `backend/`, a TanStack
frontend in `client/`, and documentation in `docs/`. The backend app has
one Spring Modulith module per Java package; the
[Backend](/backend) page maps them.

## Build the documentation

From the project root:

```sh
npm ci --prefix docs
npm --prefix docs run build
```

All documentation lives in this site (`docs/site/`): guides, the backend and
frontend pages, references and ADRs. The only other Markdown files are the root
`README.md` (quick start) and the `AGENTS.md` instructions for coding agents.

The backend image builds the site and serves it at `/docs/`
(`http://127.0.0.1:8080/docs/` with the default port), so `docker compose up
--build` refreshes it. To see edits live, run `npm --prefix docs run dev`
(`http://127.0.0.1:5180/docs/`; it has its own port so it never collides with
the app on 5173). Downloadable files (the OpenAPI draft) are listed in
`docs/scripts/build.mjs`.

VitePress 1.x and Mermaid 11 use scoped overrides for patched Vite 6 and KaTeX
releases outside their declared ranges. Recheck them when upgrading, and
validate the build, the dev server and the Mermaid diagrams before removing
them.

## Run focused backend tests

From `backend/`, using an installed Java 21:

```sh
./gradlew test
```

Integration tests use disposable Testcontainers and require Docker. The
[Backend](/backend) page cover local JDK selection,
configuration, and the dev stack.

::: tip Disposable dev environment
The dev environment is local, and its data is disposable: you may reset or
migrate the database, rebuild infrastructure or regenerate credentials. Pending
Flyway migrations run automatically when the backend starts. Never commit
credentials or expose the dev stack to a network. A LAN stack is different
while a party is on: its data belongs to real players (see
[Documentation decisions](/decisions#operational-safety)).
:::

## Run the stack

The root `README.md` is the quick start: `./scripts/provision-dev.sh --init`
once (it creates `infra/compose/.env` from `.env.example` and the local keys),
then `docker compose up -d --build` from the repository root. That runs
PostgreSQL, Redis, Kafka, the backend (serving these docs at `/docs/`) and the
app at `http://127.0.0.1:5173`. Startup applies pending migrations and seeds
an empty database with the [demo world](/demo-data); sign in by picking an
account.

- The app's container runs the client's Vite dev server with `client/`
  mounted, so edits hot-reload. It proxies `/api` (and the realtime
  WebSocket) to the backend and keeps the browser's Origin, which the backend
  checks against `PVPICKS_DEV_ORIGIN` (default the app's address; `127.0.0.1` and
  `localhost` on that port both work). See
  [Frontend](/frontend) for running it outside Docker.
- The lock worker locks bets at their deadline (and deals Blackjack hands),
  and the outbox relay publishes live updates; `PVPICKS_BET_LOCK_WORKER_ENABLED=false` or
  `PVPICKS_OUTBOX_RELAY_ENABLED=false` turns either off.
- Live scores need `PVPICKS_BALLDONTLIE_API_KEY`; without it the ticker says
  they're unavailable, and the demo's DEV games still run.
- Everything is published on 127.0.0.1 only. Don't expose the stack or the
  dev proxy to a network.

## Authentication for API development

Every mode uses an HttpOnly session cookie, same-origin checks, and a CSRF
token; [Backend](/backend) has the exact flow. Dev adds a passwordless picker
over the demo accounts. The `lan` profile is the watch-party setup behind
Caddy ([LAN watch party](/lan)). Production isn't configured yet.
