# Project roadmap

What's left, grouped by milestone. See [Project status](/status) for what's
built. Each milestone lists its exit criteria first. Work runs in parallel
where dependencies allow. No milestone has a date.

1. **LAN watch party:** one night, real players, real games, on home Wi-Fi.
2. **First production:** people play from anywhere, over weeks, and their
   data must last.
3. **Later:** everything else.

## Time-sensitive

- **Buy All-Star for NBA and NFL before the GOAT trial ends (about Nov 20).**
  Check BallDontLie's terms for showing and storing their data first. The
  switch itself is configuration only ([Player stats](/player-stats)).

## Milestone 1: LAN watch party

A single game night: about 5–15 guests in one place, on one Wi-Fi network,
for a few hours. The stack runs in Docker Compose on the owner's Mac, and
phones connect over HTTPS. Data must survive the night but can be wiped
afterwards. [LAN watch party](/lan) has the design and the runbook.

**Exit criteria.** All of these must hold:

- Every guest can open the app on their phone over trusted HTTPS, with no
  certificate setup, and sign in by scanning their QR code or typing their
  username and code.
- U2Pick, the score modes, the player modes and Scorcerer (NFL) work end to
  end on real NBA and NFL games: propose, pick, lock, resolve or wash,
  settle points and receipts.
- Every wash trigger for those modes is implemented and tested (the domain
  rules' [wash triggers](/reference/domain-rules#wash-triggers)).
- The `lan` profile starts, and nothing dev-only is reachable: no demo world,
  passwordless picker, Dev page, `/dev/**` endpoints or docs.
- A full dry run passes on the real network with at least two phones,
  following the runbook.

**Built** (2026-11-15; see [LAN watch party](/lan)):

- The `lan` profile and its Compose stack, with Caddy terminating HTTPS in
  front of the backend and a production client build. Secure cookies, an
  HTTPS-only origin, forwarded headers trusted from Caddy, LAN-only keys,
  and every dev-only feature off.
- Accounts with per-player sign-in codes (QR on the admin's cards, blur and
  rotate), an admin with an environment-set password, and a script that
  creates the guests.
- Rate limits on chat, reactions, polls, proposals, picks, search, follows,
  blocks, feedback and WebSocket subscriptions, plus the sign-in throttle
  (wrong codes count per player, with an admin **Unlock**).
- Every LAN wash trigger: Total Disaster lines start above the live combined
  score, a 48-hour no-result wash, the 7-day U2Pick wash, and Scorcerer from
  team totals with safeties ignored.
- A runbook.
- A smoke test of the whole stack on the Mac (2026-11-18, `localhost` with
  Caddy's internal certificate): HTTPS, headers, admin sign-in, creating a
  player, code and QR sign-in, rotate, and the wrong-code lockout. It found
  and fixed a bug where a rotated player's old session cookie blocked the
  new code.

**Left before the party** (owner):

- Buy All-Star for NBA and NFL (about Nov 20; see Time-sensitive above).
- Register the domain in Route 53, point `p2picks.<domain>` at the Mac and
  get the certificate.
- Fill in `infra/compose/.env.lan`: host, admin username and password
  (`PVPICKS_BOOTSTRAP_ADMIN_PASSWORD` is a new line), and the BallDontLie
  key.
- Any further mode-specific wash triggers.
- The dry run on the real Wi-Fi with at least two phones.
- No resets during the night. The disposable-environment rule applies before
  and after the party, not during it.

## Milestone 2: First production

People play from anywhere, over weeks. Data is kept and backed up.

- Hosting in the AWS project (selected Region), the same domain and TLS
  approach, and a registration policy decided after LAN (probably Google
  sign-in for players; LAN's sign-in codes stay for local events).
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
- Revoking a single session ("sign out of other devices"). Freezing and
  deleting an account already end every session.
- Make the sports plan a per-league setting that drives poll rate,
  endpoints and offered modes (ADR 0005).

## Later

**Betting**

- Choose Their Fate (NFL), designed on team totals of player stats; blocked
  until turnovers on downs can be detected
  ([Player stats](/player-stats#choose-their-fate-from-team-totals-design-blocked)).
  Scorcerer for the NBA needs GOAT play-by-play.
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
