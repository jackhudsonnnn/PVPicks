# Project status

This page describes the current implementation, not the desired final product.
The <a href="/docs/source/openapi.yaml" download>OpenAPI file</a> includes both implemented and
planned operations; it is not an implementation inventory.

## Available in the dev backend

- Login/logout, session and CSRF checks, and personal profile updates.
- The dev demo world (phase 1 of [Demo world](/demo-data)): 200 generated demo users with behavior
  profiles, the main accounts' friends, tables, blocks and invitations, a passwordless sign-in
  picker and a Reset on the Dev page. Seeded history, live demo bets and bots aren't built yet.
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
  receipts close (groups with history carry them). Every receipt and group
  lists the caller's available actions, marking ones that wait for a review.
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
- The sports feed: BallDontLie NBA and NFL snapshots (verified against live NBA
  games), polled only when `PVPICKS_BALLDONTLIE_API_KEY` is set, with raw
  responses recorded under `recordings/sports/`, game history (one snapshot
  per change, provider state, points per period), players and per-game player
  stats with history, contract tests against saved provider specs, and
  `GET /ticker`, which reports the feed as unavailable rather than empty when
  polling has failed.
- Score-mode sports bets (dev): Total Disaster and Give And Take on today's
  games, before or during the game, with picks locking at the window's end or
  the start; results and washes settle from the feed 20 minutes after Final;
  hosts can wash or set a result until points are settled
  (`POST /bets/{id}/override`), recorded on the bet and posted in chat.
- The frontend entry point supports backend sign-in, session restoration,
  table creation, invitations (join or decline), members (add followers,
  remove, host roles, permissions), table settings and deletion, a Friends
  page (settle-ups, new followers, friends/following/followers/blocked lists,
  username search, follow and block), table and bet list/detail views, U2Pick
  creation, picking, result calling,
  host points settlement, settle-up receipts with balance-change reviews, and
  table chat (paged history, text and GIF messages, reactions, unread marker
  and read position), and a live sports ticker, all updating live. Active
  picks remain private;
  participant names are loaded from the backend after lock. A Preferences page
  sets who can add you to tables, your username, avatar color and debt cap
  (shown with your current debt), plus device display settings. Deleted tables'
  chats are readable from Home for 90 days, the How to play guide sends
  feedback to the backend, and admin accounts get a site-health view instead
  of player screens. The UI follows the original prototype design: bets are
  tickets proposed from a slide-in sheet, the table page is a felt with seats
  showing each member's points (`GET /tables/{id}/balances`) and a deck of open
  bets, and a Tickets page lists bets across tables (`GET /me/bets`). Home
  shows invitation and table cards with the latest message
  (`GET /me/chat/latest`), green when you're in play, plus a live-first
  Tickets column on desktop. Friends opens with settle-up cards per person and
  a receipts dialog. Table names are limited to 14 characters. There are no
  notifications in the MVP. The
  table's side panel has the prototype's chat (bubbles, bet cards, reaction and
  GIF pickers, a ⋯ menu to report or block), Peers, and a Host panel (settle
  points, table details, members and hosts with a per-member dialog, adding
  players, Quick Pick sharing, delete) or a read-only About panel. Tapping a
  person opens a profile with their record at the table, your receipts with
  them, Report, Block and a live tomato throw. Reports reach the admin moderation
  queue, where admins dismiss them or give strikes (three freeze the
  account). Players can delete their account from Preferences once they're
  square; tomatoes are short-lived and
  block-filtered (`/tables/{id}/tomatoes`). Subscribing to another table's
  Quick Picks is not yet connected. The mock-store
  prototype was removed from the client (git tag `prototype-ui`). The client
  source is organized by feature (`client/src/features/<area>`, each with an
  `index.ts` as its only public entry, enforced by lint); see the client
  README.
- An action whose outcome is unknown (for example, a lost response) keeps its
  retry key per user in the browser's local storage, so every tab sees it and
  blocks new actions until it is retried with the same key or cleared after
  review. Other devices reconcile from the server's reads; retries are refused
  after 23 hours.

## Not yet available

- Quick Pick copy propagation and end-to-end cap enforcement.
- The player modes (Either Or, Prop Hunt, King Of The Hill) and the play-by-play modes (Choose Their Fate, Scorcerer, which need GOAT). The feed's NFL mapping still needs checking against live data.
- Chunks with player stats (stats recording began 2026-11-14). Dev scenario files and score-only chunks work now (see Dev sports data).
- The remaining frontend integrations.
- LAN/production security and deployment.

The existing picks schema stores plaintext. The [roadmap](/roadmap) tracks the
remaining decisions and dependencies.
