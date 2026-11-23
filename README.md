# P2Picks

P2Picks is a peer-to-peer betting app for friend groups. Friends sit at
tables, chat, and bet against each other on live NBA and NFL games, on custom
questions, or at a game of Jackblack. Everything is settled in points, which
have no cash value. It's a React frontend and a Spring Boot backend, run with
Docker Compose.

It runs in one of three modes:

- **dev**: on your machine, with a lived-in demo world and bots. Its data is
  disposable. That's [Run it](#run-it) below.
- **LAN**: a watch party on one Wi-Fi network, served over HTTPS from the
  host's Mac. It has been used at real parties. See
  [Host a LAN party](#host-a-lan-party).
- **prod**: not set up yet. Production is the next milestone, the MVP, after
  more security hardening and UI cleanup.

All documentation lives in the docs site (source in `docs/site/`). In dev it's
open at `/docs/`; on LAN (and later in production) it's for the admin only,
from the **Handbook** link in the admin portal.

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

## Host a LAN party

The LAN stack runs on the host's Mac and serves `https://lan.p2picks.com` to
phones on the same Wi-Fi. The name's DNS is on Cloudflare and points at the
Mac's local address, with a Let's Encrypt certificate. **LAN watch party** in
the docs has the design, the guest flow and troubleshooting. Run everything
from the repository root, with this shorthand:

```sh
alias lan="docker compose -f infra/compose/docker-compose.lan.yml --env-file infra/compose/.env.lan"
```

**First time on a Mac**

1. `./scripts/provision-lan.sh --init`, then fill in `infra/compose/.env.lan`:
   `PVPICKS_LAN_HOST=lan.p2picks.com`, the admin's username and password
   (`PVPICKS_BOOTSTRAP_ADMIN`, `PVPICKS_BOOTSTRAP_ADMIN_PASSWORD`) and the
   BallDontLie key.
2. Save a Cloudflare API token (the **Edit zone DNS** template, limited to
   `p2picks.com`) where certbot can read it and nobody else can:

   ```sh
   mkdir -p ~/.secrets && printf 'dns_cloudflare_api_token = <token>\n' > ~/.secrets/cloudflare.ini && chmod 600 ~/.secrets/cloudflare.ini
   ```

3. Get the certificate (it lasts 90 days) and copy it to Caddy:

   ```sh
   docker run --rm -v "$HOME/.p2picks-letsencrypt:/etc/letsencrypt" -v "$HOME/.secrets/cloudflare.ini:/cloudflare.ini:ro" \
     certbot/dns-cloudflare:v3.0.1 certonly --non-interactive --agree-tos --register-unsafely-without-email \
     --dns-cloudflare --dns-cloudflare-credentials /cloudflare.ini --dns-cloudflare-propagation-seconds 30 -d lan.p2picks.com
   cp -L ~/.p2picks-letsencrypt/live/lan.p2picks.com/{fullchain,privkey}.pem infra/caddy/certs/
   ```

**At each location**

1. Join the Wi-Fi and find the Mac's address: `ipconfig getifaddr en0`.
2. In Cloudflare (p2picks.com → DNS → Records), set the `lan` A record to that
   address. Keep **Proxy status** off (grey cloud): Cloudflare's proxy can't
   reach a home network. The name and the certificate stay the same, so
   guests' links and QR codes keep working.
3. Reserve that address for the Mac in the router, so it doesn't change
   during the night.
4. `lan up -d --build --wait`, then sign in at `https://lan.p2picks.com/admin`.
   Create your own player first, since the first player hosts the party table,
   then everyone else's (in Users, or with `scripts/lan-players.sh`).
5. Check one phone opens the site. If it can't find the name, the router is
   blocking public names that point at local addresses: add a local DNS entry
   for `lan.p2picks.com` on the router.

During the night, keep the Mac plugged in and awake (`caffeinate -dims`), and
watch the admin's Perf tab.

**Every 90 days**, renew the certificate and copy it again:

```sh
docker run --rm -v "$HOME/.p2picks-letsencrypt:/etc/letsencrypt" -v "$HOME/.secrets/cloudflare.ini:/cloudflare.ini:ro" certbot/dns-cloudflare:v3.0.1 renew
cp -L ~/.p2picks-letsencrypt/live/lan.p2picks.com/{fullchain,privkey}.pem infra/caddy/certs/ && lan restart caddy
```

**After the party**, optionally keep a copy
(`lan exec postgres pg_dump -U p2picks p2picks > night.sql`), then wipe it with
`lan down -v`.
