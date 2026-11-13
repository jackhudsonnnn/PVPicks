# Project status

This page describes the current implementation, not the desired final product.
The <a href="/docs/source/openapi.yaml" download>OpenAPI file</a> includes both implemented and
planned operations; it is not an implementation inventory.

## Available in the dev backend

- Provisioned dev accounts, login/logout, session and CSRF checks, and personal profile updates.
- Table creation, profile updates and reads; member addition, removal and invitations;
  host promotion/demotion, member permissions, remainder rules and betting-setting changes.
- Table deletion and a 90-day read-only chat archive for members at deletion.
- Quick Pick feature and sharing switches and code rotation, without subscription APIs or fan-out.
- Relationship and invitation reads, follow/block changes, invitation responses and preferences.
- Chat windows, text/GIF sending, reactions, and unread/read positions.
- Table bet lists and individual bet reads for current members, with Active pick privacy.
- Debt and debt-cap reads and updates, feedback, and admin health.
- Admin accounts can access admin endpoints, but not player APIs; login,
  logout and the current-session identity endpoint remain available.
- PostgreSQL schema, migrations, internal outbox storage, unexposed U2Pick
  proposal and resolution transactions, shared pick, lock and host points-
  settlement transactions, internal receipt reads/actions, and pure
  payout/debt/settlement calculators.
- An opt-in U2Pick lock worker; it is disabled by default and does not
  automatically lock sports bets.
- Proposal, pick/clear, U2Pick result and shared host points-settlement HTTP
  adapters, plus individual/group receipt history, action and private review
  adapters, exist behind an unregistered readiness marker and are exercised
  only in tests. Review acknowledgments are durable per person and revision;
  pending warnings remain discoverable after receipts close and independently
  of group-net overflow through a gated private review list. A gated
  counterparty list supports individual receipt histories during overflow.
- The frontend entry point supports backend sign-in, session restoration and
  read-only table and bet list/detail views. Active picks remain private;
  participant names are loaded from the backend after lock. The previous mock
  app is not exposed there; betting actions and other frontend workflows are
  not yet connected.
- A test-only settlement preview reads reviews and receipts and exercises their
  actions against an isolated backend. Uncertain actions retain their retry
  key in the current tab's session storage; cross-device retry recovery remains
  unfinished. The normal frontend and backend gates remain disabled.
- A separate test-only U2Pick frontend preview creates bets, places and clears
  picks against gated adapters. It shares the settlement preview's same-tab
  uncertain-action recovery and preserves Active-pick privacy. Result calling
  and points settlement are not connected in this preview.

## Not yet available

- Bet mutation and receipt APIs, default-on automatic locking, and end-to-end cap enforcement.
- Outbox relay, Kafka consumers, realtime gateway, and a production sports feed.
- Full account deletion, moderation, host-recovery flows, and the remaining frontend integrations.
- LAN/production security and deployment.

The existing picks schema stores plaintext. The [roadmap](/roadmap) tracks the
remaining decisions and dependencies.
