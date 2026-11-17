# Project status

What's built today, not the intended final product. The
<a href="/docs/source/openapi.yaml" download>OpenAPI file</a> includes both implemented and
planned operations, so it isn't an inventory of what exists.

## Available (dev only)

**Accounts and social**

- Sign-in with an eight-hour HttpOnly cookie session, CSRF and same-origin
  checks. Failed sign-ins are throttled per account name and per address
  (Redis), and so are wrong passwords when changing your password or deleting
  your account. Chat, bets, search, follows, feedback, reports, tables and
  realtime subscriptions are rate limited, and every write counts toward a
  120-a-minute ceiling per account.
- Accounts from the admin: create a player with a one-time setup link (and
  QR code), reset links, and change password. No registration. In dev, a passwordless picker over the demo accounts (see
  [Demo world](/demo-data)).
- Profile (username, color), invite preferences and debt cap.
- Follows, blocks, user search and lookup.
- Reports go to an admin moderation queue (one open report per person;
  reporting again updates it). Admins dismiss or strike them;
  three strikes freeze the account (signed out, can't sign in).
- Account deletion once you're square. Host recovery: the oldest active
  member takes over, or the table is archived.

**Tables and chat**

- Create (names up to 14 characters), edit, delete (with a 90-day read-only
  chat archive).
- Members, invitations, hosts, per-member permissions (bets, texts,
  reactions, GIFs, polls), the leftover-cents rule, betting settings and the
  polls switch.
- Membership limits: 100 tables per account, 500 members per table, 100
  waiting requests, and requests that expire after 30 days.
- Quick Pick switches and code rotation; subscriptions and copying aren't
  built.
- Chat: paged history, text and GIFs, polls (hosts can turn them off per
  table or per member), reactions, unread position, bet and settlement cards.
  Live tomatoes.

**Betting and settle-ups**

- U2Pick: propose, pick, lock at the deadline (the lock worker), call the
  result or wash.
- Sports modes on NBA and NFL games, before or during a game: the score modes
  (Total Disaster, Give And Take), the player modes (Either Or, Prop Hunt,
  King Of The Hill) and Scorcerer (NFL). They settle from the feed (most 20
  minutes after Final, King Of The Hill and Scorcerer as soon as it's
  decided), and hosts can wash or set a result until points are settled.
- Wash triggers at lock (nobody picked, everyone on one option, a line or
  target already reached) and at resolution (nobody picked the winner, ties,
  pushes, a player who didn't play), listed in the domain rules.
- Ledger, host points settlement, receipts, and settles: pick receipts with
  someone and send them (rejectable for 48 hours), mark them received or
  forgive them, with private balance-change reviews and a 3-sends-a-day
  limit per pair.

**Sports data**

- The BallDontLie NBA and NFL feed: games, game history, players and
  per-game player stats with history, the live ticker (cached in Redis), and raw-response
  recordings for replay (see [Player stats](/player-stats)).
- Dev sports data: live-edited scenario files and recorded chunks (full NBA
  and NFL games with live player stats), played as DEV games from the Dev
  page (see [Dev sports data](/dev-sports-data)).

**Delivery**

- Transactional outbox to Kafka and a STOMP/WebSocket gateway. Members get
  table, bet and chat feeds, everyone gets settlement, personal and ticker
  feeds, and every delivery rechecks the session and membership.

**Demo world (dev)**

- 250 demo users (including the 50-person Mosh Pit crowd) with play-style profiles, played by bots through the real
  services. newbie, intermediate and pro are seeded with friends, tables,
  blocks and history, the admin with open reports. Demo games are always
  live, and the Dev page has a Reset (see [Demo world](/demo-data)).

**Frontend**

- Every feature above, organized by feature folder (see the client README).
  Home with table and request cards and a Tickets column, sidebar tables and
  requests, the felt table
  page with chat, Peers and Host panels, the proposal sheet, a Tickets page,
  Friends with settle-up cards, profiles, Preferences, the guide with
  feedback, and the admin workspace (moderation, site health, players).
- Actions whose outcome is unknown keep their retry key in the browser, so
  they can be retried safely.

## Not yet available

- Quick Pick subscriptions and copying results between tables.
- Choose Their Fate (an NFL design exists, blocked on detecting turnovers on
  downs; see [Player stats](/player-stats#choose-their-fate-from-team-totals-design-blocked)),
  and Scorcerer for the NBA.
- More mode-specific wash triggers (planned by the owner; see the domain
  rules, "Wash triggers").
- Registration, and production security and deployment. The LAN watch
  party stack is built (`lan` profile behind Caddy, see
  [LAN watch party](/lan)); the domain, certificate and dry run are the
  owner's to do.

Picks are stored in plaintext. The [roadmap](/roadmap) tracks what's next.
