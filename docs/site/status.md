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
- Debt and debt-cap reads and updates, notification preferences, inbox mark-read/clear, feedback, and admin health.
- PostgreSQL schema, migrations, internal outbox storage, and pure payout/debt/settlement calculators.

## Not yet available

- Transactional bet proposal, picks, locking, settlement and cap enforcement.
- Inbox retrieval and notification delivery.
- Outbox relay, Kafka consumers, realtime gateway, and a production sports feed.
- Full account deletion, moderation, host-recovery flows, and frontend API integration.
- LAN/production security and deployment.

The existing picks schema stores plaintext. No operator-blind or sealed-pick
protocol has been implemented or selected. The [roadmap](/roadmap) tracks the
remaining decisions and dependencies.
