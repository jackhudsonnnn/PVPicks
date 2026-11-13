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

- Shared bet reads, pure calculators, storage, internal U2Pick proposal and
  resolution, shared pick, lock and host points-settlement transactions, and
  internal receipt reads and actions exist. A U2Pick lock worker is available
  only by explicit opt-in. Proposal, pick/clear, U2Pick resolution and shared
  host points-settlement and individual/group receipt HTTP adapters are gated and
  test-only; the mutation workflow and sports-feed locking are not available.
- Build U2Pick before sports bets, without depending on the sports feed. The
  first milestone includes proposal, picks, locking, outcome resolution,
  ledger writes, host points settlement, individual and grouped receipt
  actions, and forgiveness.
- Define and review authorization, deadlines and recovery for the betting
  lifecycle. Active picks must be hidden from other players and admin views.
- Implement authorization, debt-cap enforcement, lifecycle transitions, ledger
  writes and settlement receipts with concurrency and zero-sum tests.
- Preserve lock-time remainder snapshots, including the randomly chosen host.

## 4. Delivery & sports data — planned

- Build and test the outbox relay, consumer deduplication and authenticated
  realtime gateway. Outbox rows alone do not deliver events.
- Implement the sports feed and sports-only ticker; verify provider coverage,
  freshness, chronology and failure behavior before automatic results.

## 5. Frontend integration — in progress

- Backend sign-in, session restoration and read-only table and bet list/detail
  views are connected. The mock-backed app is retained in source but is not
  exposed by the current entry point.
- Receipt and durable-review interactions are connected in a test-only gated
  preview. A separate gated preview connects U2Pick creation and picking.
  They share same-tab uncertain-action recovery; cross-device recovery, result
  calling and points settlement remain to be completed before exposure.
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
