# Project roadmap

Work may proceed in parallel where dependencies permit. Status describes the
current code, not an estimated delivery date.

## 1. Documentation & foundations — in progress

- Maintain this handbook, the current domain rules, ADRs and draft contracts.
- Add an interactive API reference later, after defining request execution and
  access safeguards; the current site links to the draft OpenAPI file.
- Complete API and realtime contract review and finish dev infrastructure
  verification without modifying shared data.

## 2. Tables & social — in progress

- **Available:** table creation, profile updates and reads, member additions, removal and invitations,
  deletion and read-only chat archives, host/permission/remainder and betting-setting changes, Quick Pick controls, relationships,
  invitation responses, personal profile updates, user search and lookup, preferences and chat basics.
- **Later:** Quick Pick subscriptions and copying, archive data cleanup policy, and host recovery.

## 3. Betting & settlement — in progress

- **Available:** U2Pick proposal, picks, deadline locking by the dev lock
  worker, member-called results and washes, ledger writes, host points
  settlement, and individual/group receipt actions with private balance-change
  reviews, over HTTP and in the frontend.
- **Later:** sports-bet modes and their feed-driven locking and results, Quick
  Pick copy propagation, and end-to-end debt-cap enforcement.
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
  it to resolve sports bets.

## 5. Frontend integration — in progress

- Backend sign-in, session restoration, table and bet views, U2Pick betting,
  result calling, host points settlement and settle-up receipts are connected.
  The mock-backed app is retained in source but is not exposed by the current
  entry point.
- Uncertain actions are saved per user in local storage and shared across
  tabs; other devices reconcile from server reads.
- Integrate one real workflow at a time; validate recovery, permissions, mobile
  layout and accessibility. Prototype completion is not backend completion.
- See the <a href="/docs/source/frontend-roadmap.md" download>frontend roadmap</a>
  for integration work.

## 6. Security, operations & launch — planned

- Complete account deletion, legacy system-message cleanup, moderation,
  session revocation and host recovery.
- Design retention and rotation for retry records and audit data.
- Add rate limits, secure cookies, TLS and operational protections before LAN.
  Validate backup/recovery and observability before production or AWS.
- Preserve existing data; destructive resets require explicit approval.
