# Project roadmap

Where the project is and what's left, grouped by milestone. See
[Project status](/status) for what's built. Work runs in parallel where
dependencies allow. No milestone has a date.

1. **LAN watch party:** done. Real parties have run.
2. **MVP: production:** people play from anywhere, over weeks, and their data
   must last. Security hardening and UI cleanup come first.
3. **Later:** everything else.

## Milestone 1: LAN watch party (done)

A game night for about 5–15 guests on one Wi-Fi network, served from the
host's Mac over trusted HTTPS at `https://lan.p2picks.com`.
[LAN watch party](/lan) has the design, and the root README has the hosting
steps. Real parties have run (owner, 2026-11-21).

What it delivered:

- The `lan` profile and its Compose stack, with Caddy terminating HTTPS in
  front of the backend and a production client build. It has secure cookies,
  an HTTPS-only origin, forwarded headers trusted from Caddy, LAN-only keys,
  and every dev-only feature off.
- DNS on Cloudflare: one fixed name, whose A record is moved to the Mac's
  address at each location. A Let's Encrypt certificate comes from a DNS
  challenge and lasts 90 days.
- Accounts with per-player sign-in codes (a QR code on the admin's cards,
  blurred and rotatable), an admin with an environment-set password at
  `/admin`, admin account deletion, and the party table: the first player
  hosts it, and everyone after is seated as everyone's friend.
- Rate limits and the sign-in throttle; every LAN wash trigger; Blackjack;
  the slur filter.
- BallDontLie All-Star for the NBA and the NFL.

## Milestone 2: MVP: production

People play from anywhere, over weeks. Data is kept and backed up. Three
tracks, in parallel where they can be:

**Security hardening** (owner's list to come; what the docs already name):

- Revoking a single session ("sign out of other devices"). Freezing and
  deleting an account already end every session.
- Picks are stored in plaintext.
- A security review of the production profile before launch: headers,
  cookies, origins, secrets and exposed routes, like the LAN review.

**UI cleanup** (owner's list to come).

**Production setup:**

- Hosting in the AWS project (selected Region), with HTTPS on the project's
  domain. Players sign in with Google; LAN's sign-in codes stay for local
  events.
- Backups, restore drills and retention for retry records, audit data,
  archives and outbox markers.
- Observability: metrics and logs through OpenTelemetry (not Kafka),
  consumer lag and a Kafka check in the admin Perf tab
  ([Kafka & Redis](/kafka-redis), item 5). The Perf tab's own timings
  (`perf_minutes`, 7 days) stay as the in-app view.
- Historic sports data: a backfill job that pulls past games, snapshots and
  player stats from the provider into the admin sports explorer, season by
  season. It's slow (a season may take a day at the provider's rate limits),
  so it runs in the background and resumes where it stopped.
- More than one backend instance: one runner per scheduled job and a shared
  sports-feed request budget in Redis (item 3).
- Event-driven sports resolution, then the production partition count and
  topics split by domain (items 6–7).
- Make the sports plan a per-league setting that drives poll rate,
  endpoints and offered modes (ADR 0005).

## Later

**Betting**

- Choose Their Fate (NFL), designed on team totals of player stats; blocked
  until turnovers on downs can be detected
  ([Player stats](/player-stats#choose-their-fate-from-team-totals-design-blocked)).
- Quick Pick subscriptions and copying results between tables, with
  end-to-end debt-cap enforcement across copies.
- Maybe: a host setting for who may override sports results; showing
  host-changed outcomes in the settlement message.
- Every mode reuses the shared pick, privacy, cap, lock, payout and
  settlement transactions; only the feed supplies sports outcomes.

**Sports data**

- Check the recordings: how fast stats update, corrections after Final,
  and NFL status and quarter handling on a live Sunday.
- Cut finished games into chunks, then remove that day's raw recordings.

**Social, tables and delivery**

- An archive data cleanup policy.
- Keep reactions on older loaded chat messages current. Today a change
  refreshes the newest window and the message you reacted to.
- Ordering and gap detection beyond refetch-on-reconnect, and durable
  consumers that record processed events.

**Documentation**

- An interactive API reference, once request execution and access
  safeguards are defined. Finish the API contract review.
