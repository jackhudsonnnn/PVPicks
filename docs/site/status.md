# Project status

What's built today, not the intended final product. The
<a href="/docs/source/openapi.yaml" download>OpenAPI file</a> includes both implemented and
planned operations, so it isn't an inventory of what exists.

## Available (dev only)

**Accounts and social**

- Sign-in with an eight-hour HttpOnly cookie session, CSRF and same-origin
  checks. In dev, a passwordless picker over the demo accounts (see
  [Demo world](/demo-data)).
- Profile (username, color), invite preferences and debt cap.
- Follows, blocks, user search and lookup.
- Reports go to an admin moderation queue. Admins dismiss or strike them;
  three strikes freeze the account (signed out, can't sign in).
- Account deletion once you're square. Host recovery: the oldest active
  member takes over, or the table is archived.

**Tables and chat**

- Create (names up to 14 characters), edit, delete (with a 90-day read-only
  chat archive).
- Members, invitations, hosts, per-member permissions, the leftover-cents
  rule and betting settings.
- Quick Pick switches and code rotation; subscriptions and copying aren't
  built.
- Chat: paged history, text and GIFs, reactions, unread position, bet and
  settlement cards. Live tomatoes.

**Betting and settle-ups**

- U2Pick: propose, pick, lock at the deadline (the lock worker), call the
  result or wash.
- Score modes on NBA and NFL games: Total Disaster and Give And Take, before
  or during a game. They settle from the feed 20 minutes after Final, and
  hosts can wash or set a result until points are settled.
- Ledger, host points settlement, receipts (individual and per person) with
  private balance-change reviews. The server says which receipt actions you
  can take.

**Sports data**

- The BallDontLie NBA and NFL feed: games, game history, players and
  per-game player stats with history, the live ticker, and raw-response
  recordings for replay (see [Player stats](/player-stats)).
- Dev sports data: live-edited scenario files and recorded chunks, played as
  DEV games from the Dev page (see [Dev sports data](/dev-sports-data)).

**Delivery**

- Transactional outbox to Kafka and a STOMP/WebSocket gateway. Members get
  table, bet and chat feeds, everyone gets settlement, personal and ticker
  feeds, and every delivery rechecks the session and membership.

**Demo world (dev)**

- 200 demo users with play-style profiles, played by bots through the real
  services. newbie, intermediate and pro are seeded with friends, tables,
  blocks and history, the admin with open reports. Demo games are always
  live, and the Dev page has a Reset (see [Demo world](/demo-data)).

**Frontend**

- Every feature above, organized by feature folder (see the client README).
  Home with table and invitation cards and a Tickets column, the felt table
  page with chat, Peers and Host panels, the proposal sheet, a Tickets page,
  Friends with settle-up cards, profiles, Preferences, the guide with
  feedback, and the admin workspace (moderation, site health).
- Actions whose outcome is unknown keep their retry key in the browser, so
  they can be retried safely.

## Not yet available

- Quick Pick subscriptions and copying results between tables.
- The player-stat modes (Either Or, Prop Hunt, King Of The Hill), and the
  play-by-play modes (Choose Their Fate, Scorcerer), which need GOAT.
- Player stats on demo games and in recorded chunks. Stats have been recorded
  since 2026-11-14.
- Registration, and LAN or production security and deployment.

Picks are stored in plaintext. The [roadmap](/roadmap) tracks what's next.
