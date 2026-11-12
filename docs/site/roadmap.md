# Project roadmap

Work may proceed in parallel where dependencies permit. Status describes the
current code, not an estimated delivery date. The historical v3 sequence is
reference material, not a requirement to wipe data or finish every earlier
phase before starting later work.

## 1. Documentation & foundations — in progress

- Maintain this handbook, the current domain rules, ADRs and draft contracts.
- Add an interactive API reference later, after defining request execution and
  access safeguards; the current site links to the draft OpenAPI file.
- Reconcile remaining historical v3 requirements and the frontend test crosswalk
  into current decisions; do not silently discard unresolved rules.
- Complete API and realtime contract review and finish dev infrastructure
  verification without modifying shared data.

## 2. Tables & social — in progress

- **Available:** table creation, profile updates and reads, member additions, removal and invitations,
  deletion and read-only chat archives, host/permission/remainder and betting-setting changes, Quick Pick controls, relationships,
  invitation responses, personal profile updates, user search and lookup, preferences and chat basics.
- **Later:** Quick Pick subscriptions and copying, archive data cleanup policy, and host recovery.

## 3. Betting & settlement — foundational pieces only

- Pure calculators and storage exist; transactional bet proposal, picks,
  locking, outcomes and settlement are not implemented.
- Design and independently review the stronger pre-lock privacy target before
  claiming it. Resolve release trust, deadlines, failure and recovery behavior.
- Implement authorization, debt-cap enforcement, lifecycle transitions, ledger
  writes and settlement receipts with concurrency and zero-sum tests.
- Preserve lock-time remainder snapshots, including the randomly chosen host.

## 4. Delivery & sports data — planned

- Build and test the outbox relay, consumer deduplication, notification delivery
  and authenticated realtime gateway. Outbox rows alone do not deliver events.
- Implement the sports feed and sports-only ticker; verify provider coverage,
  freshness, chronology and failure behavior before automatic results.

## 5. Frontend integration — planned

- Keep the browser-local prototype separate until an API adapter and async
  state/error flows are ready.
- Integrate one real workflow at a time; validate recovery, permissions, mobile
  layout and accessibility. Prototype completion is not backend completion.
- The <a href="/docs/source/frontend-roadmap.md" download>prototype's existing roadmap</a> is a
  historical frontend work list, not this project's implementation status.

## 6. Security, operations & launch — planned

- Complete account deletion, legacy system-message cleanup, moderation,
  session revocation and host recovery.
- Design retention and rotation for retry records, notifications and audit data.
- Add rate limits, secure cookies, TLS and operational protections before LAN.
  Validate backup/recovery and observability before production or AWS.
- Preserve existing data; destructive resets require explicit approval.
