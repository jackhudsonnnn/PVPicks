# Project roadmap

Work may proceed in parallel where dependencies permit. Status describes the
current code, not an estimated delivery date.

## 1. Documentation & foundations — in progress

- Maintain this handbook, the current domain rules, ADRs and draft contracts.
- Add an interactive API reference later, after defining request execution and
  access safeguards; the current site links to the draft OpenAPI file.
- Complete the API contract review.

## 2. Tables & social — in progress

- **Available:** table creation, profile updates and reads, member additions, removal and invitations,
  deletion and read-only chat archives, host/permission/remainder and betting-setting changes, Quick Pick controls, relationships,
  invitation responses, personal profile updates, user search and lookup, preferences and chat basics.
- **Available (2026-11-14):** an admin moderation queue (dismiss, or strike; three strikes freeze, which blocks sign-in), account deletion once you're square, and host recovery (the oldest active member takes over, or the table is archived).
- **Later:** Quick Pick subscriptions and copying, and an archive data cleanup policy.

## 3. Betting & settlement — in progress

- **Available:** U2Pick proposal, picks, deadline locking by the dev lock
  worker, member-called results and washes, ledger writes, host points
  settlement, and individual/group receipt actions with private balance-change
  reviews, over HTTP and in the frontend.
- **Later:** sports-bet modes and their feed-driven locking and results, with
  the decided automatic outcomes and host overrides (domain rules, "Sports
  outcomes and host overrides"); Quick Pick copy propagation; end-to-end
  debt-cap enforcement.
- Sports modes must reuse the shared pick, privacy, cap, lock, payout and
  settlement transactions; only the feed may supply their outcomes.
- Preserve lock-time remainder snapshots, including the randomly chosen host,
  and keep Active picks hidden from other players and admin views.

## 4. Delivery & sports data — in progress

- **Available:** the outbox relay to Kafka with seven-day cleanup of published
  rows, a realtime consumer, and an authenticated STOMP gateway delivering
  every outbox event to its reviewed audience; the BallDontLie NBA/NFL feed and
  the sports ticker, live in the frontend.
- **Later:** ordering and gap detection beyond refetch-on-reconnect; durable
  consumers that record processed events; verify the feed's NFL fields,
  coverage, chronology and per-game statistics against live data before using
  it to resolve sports bets; game history, rosters, stored player stats and a
  dev replay feed (ADR 0005). All-Star is chosen for both leagues; raw player
  box scores are already recorded during games.
- **Next:** the player modes (Either Or, Prop Hunt, King Of The Hill) on the
  stored player stats. Done: saved specs and contract tests, sports data
  storage, the shared sports-bet lifecycle, the host override, Total Disaster
  and Give And Take, and dev sports data (scenario files and recorded chunks
  as DEV games, with a Dev page).

## 5. Frontend integration — in progress

- Backend sign-in, session restoration, tables, members, invitations,
  friends, preferences, table chat and archives, U2Pick betting, result
  calling, host points settlement, settle-up receipts, the guide with feedback
  and an admin site-health view are connected. The mock-store prototype was
  removed (git tag `prototype-ui`).
- Uncertain actions are saved per user in local storage and shared across
  tabs; other devices reconcile from server reads.
- Home matches the prototype: table and invitation cards (green when you're
  in play), and on desktop a live-first Tickets column.
- Settle-ups follow the prototype: a card per person on Friends and a
  receipts dialog with per-receipt and group actions.
- Out of scope for the MVP (owner decision, 2026-11-14): notifications. The
  prototype's bell is not coming back; live updates and Home's cards cover
  what changed.
- Integrate one real workflow at a time; validate recovery, permissions, mobile
  layout and accessibility. Prototype completion is not backend completion.
- See the <a href="/docs/source/frontend-roadmap.md" download>frontend roadmap</a>
  for integration work.

- Demo world (implemented; see [Demo world](/demo-data)): 200 demo users
  with behavior profiles played by bots, seeded main accounts and history,
  always-live demo games, passwordless dev sign-in and a Reset on the Dev
  page. Not yet: player stats on demo games.

## 6. Security, operations & launch — planned

- Legacy system-message cleanup, and revoking a single session (sign out of
  other devices). Freezing and deleting accounts already end every session.
- Design retention and rotation for retry records and audit data.
- Add rate limits, secure cookies, TLS and operational protections before LAN.
  Validate backup/recovery and observability before production or AWS.
- Until then the dev environment is disposable; before LAN, switch to preserving
  data and approving destructive operations.
