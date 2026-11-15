# P2Picks

P2Picks is a peer-to-peer betting app for friend groups: a React frontend and
a development-only Spring Boot backend. It's pre-MVP and not ready for real
users or a network: everything runs locally in Docker, and the dev data is
disposable.

All documentation lives in the docs site (source in `docs/site/`).

## Run it

You need Docker with Compose. From the repository root:

1. **First time only:** create your local settings and secrets.

   ```sh
   ./scripts/provision-dev.sh --init
   ```

   This creates, in `infra/compose/` (git-ignored, readable only by you):

   - `.env`: your settings, copied from
     [`infra/compose/.env.example`](infra/compose/.env.example) with a random
     database password;
   - `.env.auth` and `.env.idempotency`: the session-signing and
     retry-fingerprint keys.

   Running it again keeps existing files. For live NBA and NFL scores, add a
   BallDontLie key to `.env`:

   ```sh
   PVPICKS_BALLDONTLIE_API_KEY=<your key>
   ```

   Every other setting is optional; the example file lists them with their
   defaults (ports, polling rates, workers). Never commit these files.

2. **Start everything:**

   ```sh
   docker compose up -d --build
   ```

   This starts PostgreSQL, Redis, Kafka, the backend (which also serves the
   docs) and the app. The first build takes a few minutes. An empty database
   is seeded with the demo world on startup.

3. **Stop:** `docker compose down`. Add `-v` to also delete the database.

`docker compose ps` shows what's running, and `docker compose logs -f backend`
follows the backend. If a port is taken, set `PVPICKS_PORT` (backend, default
8080) or `PVPICKS_CLIENT_PORT` (app, default 5173) in `infra/compose/.env`,
then run `docker compose up -d` again.

### a) The documentation site

Click the **document icon** beside the P2Picks logo (on the sign-in page too),
or open **http://127.0.0.1:5173/docs/**: the app's dev server passes `/docs/`
through to the backend. The backend's own address works as well
(http://127.0.0.1:8080/docs/, or your `PVPICKS_PORT`; `docker compose port
backend 8080` prints it). The docs are built into the backend image, so
`docker compose up -d --build` refreshes them. Start with
**Getting started**; **Project status** says what's built.

To see doc edits live while writing, run the docs dev server instead:

```sh
npm ci --prefix docs        # once
npm --prefix docs run dev   # http://127.0.0.1:5180/docs/
```

### b) P2Picks

Open **http://127.0.0.1:5173** (or your `PVPICKS_CLIENT_PORT`; `docker compose
port client 5173` prints it). The sign-in
page lists the demo accounts: pick **newbie**, **intermediate**, **pro** or
**admin**, or search the 200 demo users. There are no passwords, and if
something asks for one, it's `demo`.

The app's source (`client/`) is mounted into its container, so edits reload
in the browser. The **Dev** page (flask icon beside the logo, also on the
sign-in page, no sign-in needed) resets the demo world and runs test games. See **Demo world** and **Dev sports data** in the
docs.
