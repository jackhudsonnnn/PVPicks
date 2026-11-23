# Project status

What's built today, not the intended final product. The
<a href="/docs/source/openapi.yaml" download>OpenAPI file</a> includes both implemented and
planned operations, so it isn't an inventory of what exists.

## Available

Everything here runs in dev and on LAN, unless marked as one or the other.

**Accounts and social**

- Sign-in with an eight-hour HttpOnly cookie session, CSRF and same-origin
  checks. Failed sign-ins are throttled per account name and per address
  (Redis), and wrong sign-in codes per address. Chat, bets, search, follows, feedback, reports, tables and
  realtime subscriptions are rate limited, and every write counts toward a
  120-a-minute ceiling per account.
- Accounts from the admin: each player has a 4-character sign-in code shown
  as a QR code on their admin card (blurred until shown, rotatable, which
  signs them out). Players have no password; only the admin does. No
  registration. In dev, a passwordless picker over the demo accounts (see
  [Demo world](/demo-data)). The admin signs in at `/admin` and can delete a
  player outright (their open bets wash).
- Three modes, dev, LAN and prod, read from the backend's profile and served
  at `GET /config` (prod isn't configured yet; see
  [Architecture](/architecture#three-modes-dev-lan-prod)). On the LAN, the
  first player created hosts a party table and every later one is seated
  there as everyone's friend ([LAN watch party](/lan#the-party-table)).
- Profile (username, color), invite preferences and points limit.
- Follows, blocks, user search and lookup.
- Slurs and hate speech are refused in chat, polls, U2Pick questions and
  options, table names and usernames (swearing and trash talk are fine; see
  the domain rules, "Abusive language filter").
- Reports go to an admin moderation queue (one open report per person;
  reporting again updates it). Admins dismiss or strike them;
  three strikes freeze the account (signed out, can't sign in).
- Account deletion once you're square. Host recovery: the oldest active
  member takes over, or the table is archived.

**Tables and chat**

- Create (names up to 14 characters), edit, delete (with a 90-day read-only
  chat archive).
- Members, invitations, hosts, per-member permissions (bets, texts,
  reactions, GIFs, polls), betting settings and the
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
  King Of The Hill) and Scorcerer (which team scores next and how: NFL
  touchdown or field goal, NBA 3 or 2). They settle from the feed (most 20
  minutes after Final, King Of The Hill and Scorcerer as soon as it's
  decided), and hosts can wash or set a result until points are settled.
- Jackblack (replaced Blackjack): everyone who joins deals once per rotation,
  in a random order, and banks that round against the rest. Choices are made
  in lockstep (new cards come out once everyone has chosen or the timer runs
  out), the round's dealer flips, hits and stands with one button, and each
  player's worst case is held against their points limit. Hosts choose who
  can propose it. It's played on the ticket; the felt shows each player's
  total and result (see the domain rules, "Jackblack").
- Hold'em: no-limit Texas hold'em over 1–3 rotations (a hand per player each
  time round), 100 big blinds to buy in, one rebuy per game, a turn timer,
  server-side pre-moves, and a shuffle audit revealed once the game is over.
  The proposer sets the players range; hosts choose who can propose it (see
  the domain rules, "Hold'em").
- Payouts: winners split the losers' pot and leftover cents go to winners at
  random. Every mode follows the fairness rule (domain rules, "Fairness").
- Wash triggers at lock (nobody picked, everyone on one option, a line or
  target already reached) and at resolution (nobody picked the winner, ties,
  pushes, a player who didn't play), listed in the domain rules.
- Ledger, host points settlement, receipts, and settles: pick receipts with
  someone and settle them (rejectable for 48 hours) or mark them as settled,
  with private balance-change reviews and a 3-a-day limit per pair. Points
  have no cash value; the copy never mentions paying.

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

- 250 demo users (including the 50-person Mosh Pit crowd) with play-style
  profiles, played by bots through the real services, Jackblack and Hold'em included. newbie, intermediate and pro are seeded with friends, tables,
  blocks and history, the admin with open reports and feedback. Demo games are always
  live, and the Dev page has a Reset (see [Demo world](/demo-data)).

**Frontend**

- Every feature above, organized by feature folder (see [Frontend](/frontend)).
  Home with table and request cards and a Tickets column, sidebar tables and
  requests, the felt table
  page with chat, Peers and Host panels, the proposal sheet, a Tickets page,
  Friends with settle-up cards, profiles, Preferences, the guide with
  feedback, and the admin workspace: player audits with a logged audit
  trail, moderation and a feedback inbox, API and browser performance over 7
  days, and a sports data explorer (see [Admin dashboard](/admin)).
- Actions whose outcome is unknown keep their retry key in the browser, so
  they can be retried safely.

## Not yet available

- Quick Pick subscriptions and copying results between tables.
- Choose Their Fate (an NFL design exists, blocked on detecting turnovers on
  downs; see [Player stats](/player-stats#choose-their-fate-from-team-totals-design-blocked)).
- More mode-specific wash triggers (planned by the owner; see the domain
  rules, "Wash triggers").
- Production: hosting, backups, observability and Google sign-in for
  players (the MVP milestone, with more security hardening and UI cleanup;
  see the [roadmap](/roadmap)). LAN is done: real parties have run on
  `https://lan.p2picks.com` ([LAN watch party](/lan)).

Picks are stored in plaintext. The [roadmap](/roadmap) tracks what's next.
