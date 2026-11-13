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
- U2Pick betting over HTTP: proposal, pick and clear, member-called results
  and washes, host points settlement, and individual/group receipt history,
  actions and private balance-change reviews. Review acknowledgments are
  durable per person and revision; pending warnings remain discoverable after
  receipts close and independently of group-net overflow through the private
  review list, and a counterparty list supports individual receipt histories
  during overflow.
- The U2Pick lock worker, on by default in the dev profile
  (`PVPICKS_BET_LOCK_WORKER_ENABLED=false` turns it off). It does not lock
  sports bets.
- PostgreSQL schema, migrations, the transactional outbox and pure
  payout/debt/settlement calculators.
- Realtime delivery: an outbox relay to Kafka (on by default in dev;
  `PVPICKS_OUTBOX_RELAY_ENABLED=false` turns it off), a consumer, and an
  authenticated STOMP/WebSocket gateway. Members get table view, bet and chat
  feeds; each user gets a private settlement feed, a personal feed (table list,
  invitations, profiles of people they share a table or receipt with, minus
  blocks) and the ticker feed. Every delivery rechecks the session and
  membership. Published outbox rows are deleted after seven days.
- The sports feed: BallDontLie NBA and NFL snapshots, polled only when
  `PVPICKS_BALLDONTLIE_API_KEY` is set, and `GET /ticker`, which reports the
  feed as unavailable rather than empty when polling has failed.
- The frontend entry point supports backend sign-in, session restoration,
  table and bet list/detail views, U2Pick creation, picking, result calling,
  host points settlement, settle-up receipts with balance-change reviews, and
  table chat (paged history, text and GIF messages, reactions, unread marker
  and read position), and a live sports ticker, all updating live. Active
  picks remain private;
  participant names are loaded from the backend after lock. The previous mock
  app is not exposed there; table management, social and other frontend
  workflows are not yet connected.
- An action whose outcome is unknown (for example, a lost response) keeps its
  retry key per user in the browser's local storage, so every tab sees it and
  blocks new actions until it is retried with the same key or cleared after
  review. Other devices reconcile from the server's reads; retries are refused
  after 23 hours.

## Not yet available

- Sports-bet modes and locking, Quick Pick copy propagation, and end-to-end cap enforcement.
- Sports-bet modes, feed-driven results and host overrides (rules decided; see the domain guide); the feed's NFL mapping still needs checking against live data.
- Sports data history, rosters, player stats and a dev replay feed (ADR 0005, proposed).
- Full account deletion, moderation, host-recovery flows, and the remaining frontend integrations.
- LAN/production security and deployment.

The existing picks schema stores plaintext. The [roadmap](/roadmap) tracks the
remaining decisions and dependencies.
