# Backend contract inventory — Phase 0 draft

This maps the current frontend seam, not an OpenAPI specification or a list of
implemented endpoints. Proposed paths still need request/response schemas,
authorization, validation, error semantics and review.

`../../backend/api/openapi.yaml` contains preliminary identity, debt-cap, betting,
table, Quick Picks, chat, social, feedback, game and settlement
operations, an admin health snapshot and a draft admin user-action operation.
Other admin reads and surfaces remain incomplete; it is not ready to generate
a client.
`realtime-contract.md` records the realtime gateway's destinations and rules.

Source: the prototype's `client/src/features/store/commands.ts`, removed from
the client and preserved at the git tag `prototype-ui`. The command names below
are kept as an inventory of intended operations. The backend-backed app
already uses sign-in, tables, members, invitations, social, preferences, chat
and archives, U2Pick proposal, picks and results, host points settlement,
receipt reviews and actions, feedback and the admin health snapshot; the
remaining commands still need endpoints, screens, or both.

## Commands

| Frontend command | Intended backend operation |
|---|---|
| `setDebtCap` | `PUT /me/debt-cap`; `GET /me/debt` for current debt |
| `propose` | Dev `POST /tables/{id}/bets` (U2Pick only) |
| `pick` | Dev `PUT /bets/{id}/pick`; `DELETE /bets/{id}/pick` for No guess |
| `settle` | Dev `POST /bets/{id}/settle` for authorized U2Pick settlers only |
| `simulateResult` | Removed with the prototype; replayed feed data (ADR 0005) replaces it in dev; no player endpoint |
| `overrideQuickPick` | Planned host override (wash or set a result) for sports bets and Quick Pick copies until points are settled; endpoint not yet designed |
| `send`, `sendGif` | Dev `POST /tables/{id}/messages` |
| `react` | Dev `PUT`/`DELETE /messages/{id}/reaction` |
| `updateProfile` | Dev `PATCH /me/profile` |
| `follow` | `POST`/`DELETE /follows/{userId}` |
| `block` | `POST /blocks/{userId}`; proposed `DELETE /blocks/{userId}` |
| `deleteAccount` | `DELETE /me` |
| `setInvitePrefs` | `PUT /me/invite-preferences` |
| `respondInvite` | `POST /invites/{id}/respond` |
| `createTable` | Dev `POST /tables`, including requested initial members |
| `updateTable` | Dev `PATCH /tables/{id}` |
| `addMembers` | Dev `POST /tables/{id}/members` |
| `removeMember` | Dev `DELETE /tables/{id}/members/{userId}` with unsettled-bet and host guards |
| `deleteTable` | Dev `DELETE /tables/{id}`; 409 for Active/Pending bets or unresolved points settlement |
| `toggleHost` | Dev `PUT`/`DELETE /tables/{id}/hosts/{userId}` |
| `setPermission` | Dev `PUT /tables/{id}/members/{userId}/permissions` |
| `setRemainderMode` | Dev `PATCH /tables/{id}/remainder`; 409 for a change while any bet is Active |
| `setHostsOnlyBets`, `setU2PickOff` | Dev `PATCH /tables/{id}/settings` |
| `setQuickPicks`, `setSharing` | Dev `PUT /tables/{id}/quick-picks` and `POST`/`DELETE /tables/{id}/quick-picks/share`; rotation is `POST /tables/{id}/quick-picks/rotate`. |
| `subscribe` | `POST /tables/{id}/quick-picks/subscribe` with code |
| `unsubscribe` | `DELETE /tables/{id}/quick-picks/subscribe?sourceTableId=...` |
| `settlePoints` | Dev `POST /tables/{id}/settle-points` |
| `settlePerson` | Dev `POST /people/{id}/settle` with step and expected revision |
| `reviewSettleChange` | Dev `GET /users/{id}/settlement-review` and idempotent `POST /users/{id}/settlement-review/acknowledge` |
| `markSent`, `confirmPaid`, `disputePaid`, `forgive`, `askForgive` | Dev `POST /settlement-lines/{id}/{action}` with revision |
| `declineForgive` | Dev `POST /settlement-lines/{id}/decline-forgive` with revision |

## Other contract surfaces

- Authentication: login, logout, current user and secure session lifecycle.
  Dev and LAN use provisioned accounts; decide production sign-up after LAN.
  The dev-only eight-hour cookie session, CSRF flow, login, logout and `/me`
  are implemented. The dev security filter checks the current database role
  for `/admin` routes; only the admin health snapshot exists. The wider
  contract remains a draft. LAN refresh-token and TLS policy remain to be
  designed.
- Initial state and read APIs for tables, users, bets, games, follows, blocks,
  invitations and permissions; paginated history and chat.
  Invitees are not table members and cannot read the full table; the invite
  list supplies its name and emoji for the pending-invite screen. Dev
  `GET /tables` and `GET /tables/{id}` are implemented for active memberships:
  hosts see the Quick Pick code and all disabled permissions, other members see
  only their own disabled permissions, and missing, archived and non-member
  tables all return 404. `GET /me/relationships` returns the caller's private
  relationship view, suppressing follows across blocks and to deleted users, and
  `GET /me/invites` returns visible pending invitations. `GET /tables/archives`
  lists available read-only chat archives. Other reads remain incomplete.
- Dev `GET /ticker` returns today's New York games plus earlier live games,
  live first, and 503 `feed_unavailable` unless every league polled
  successfully within the freshness window (default three minutes).
  `ticker.changed` on `/user/queue/ticker` invalidates it. The ticker contains
  no bet activity. `GET /games` remains unimplemented.
  Feed freshness and partial-feed handling still require review.
- Dev `GET`/`PUT /tables/{id}/last-read` supplies filtered unread count and
  first unread message ID and advances only to a currently visible message.
  Last-read endpoints do not support archives.
- Dev `GET /chat/{id}/messages` returns filtered latest (30), around (up to 30)
  and before/after (up to 25) windows. Its opaque cursors are encrypted and
  user/table-bound, with a key derived from the dev idempotency secret; every
  nonempty page has a next cursor for later arrivals. Around takes a visible
  message ID. Bet cards contain no picks and settlement cards only static
  amounts and parties. Deleted users' reactions are excluded; blocked users'
  reactions on visible messages remain.
  Archive members may read for 90 days after deletion.
  Chat changes publish a `chat.changed` invalidation on the table's chat feed.
- Dev reaction PUT and DELETE require current membership and react permission,
  filter blocked text and GIF authors, and permit bet cards. PUT accepts one
  ICU4J 76.1 RGI emoji sequence. A matching retry rechecks access without
  reapplying. Reaction changes invalidate the table's chat feed.
- Dev chat sending validates the current text/GIF permission, trims text to
  1–2000 Unicode code points and restricts GIF IDs to the prototype catalog.
  A retry rechecks access and returns the original 201 representation without
  resending; if the message was removed or its content changed, it returns 404.
  The retry record stores a message reference, not a copy of the text. Rate
  limiting remains unimplemented.
- Dev member-permission PUT lets hosts quietly change the four permissions of
  current non-host members, including frozen members. Retries recheck the
  current host and target role without reapplying. Chat and reactions enforce
  their permissions; internal betting transactions also enforce relevant permissions.
- Dev remainder PATCH rejects a change while an Active bet exists, leaves
  Pending snapshots intact and creates a system chat message when changed.
  An unchanged value and a matching authorized retry are no-ops, including
  while a bet is Active. Internal bet proposal and locking enforce this setting.
- Dev betting-settings PATCH lets a current host change either switch, adds
  neutral system messages for actual changes, and returns the current table.
  Retries recheck host access and do not reapply; existing bets are unchanged.
  Future proposals and Quick Pick fan-out must enforce the switches.
- Dev Quick Pick controls enable sharing with the existing code when the
  feature is enabled. Disabling the feature removes both directions of
  subscription; stopping sharing or rotating removes incoming subscriptions.
  Existing copies remain. Authorized matching retries do not reapply; rotation
  retries return the current code. Subscriptions, backfill and fan-out are
  unimplemented, and historical code reuse needs a safe design.
- Dev member removal requires a current host and a non-host target; self-removal
  is not provided. It rejects removal while any bet is Active or Pending or a
  resolved non-4fun bet awaits points settlement. It preserves records, open
  receipts and chat read position, cancels pending target invitations and
  writes a neutral notice. A matching retry rechecks actor access but never
  removes a subsequently readded member. Realtime deliveries recheck membership,
  so the removed member's table feeds stop; no table-list invalidation exists yet.
- Dev table-profile PATCH updates a name and/or emoji with the same validation
  as table creation. It returns the current table on an authorized retry and
  writes a neutral system message only for an actual change. Pending invitation
  reads use the current table details.
- Dev personal-profile PATCH normalizes usernames, enforces uniqueness and
  changes hue. Renames release the old username and affect future logins;
  existing sessions remain active. Matching retries return the current profile.
  Attributed host-change messages resolve the new name, but legacy free-text
  messages are not rewritten. Profile outbox events are not delivered.
- Dev `GET /users` searches literal username substrings, excludes the caller,
  deleted users and blocks in either direction, and includes frozen users
  without exposing status. It uses 50-item username-keyset pages, not a
  snapshot; concurrent renames and visibility changes can affect pagination.
  Search rate limiting remains unimplemented.
- Dev individual-user lookup resolves outgoing blocks, including mutual
  blocks, and incoming-only blocks only for shared current table members or
  direct receipt parties. Deleted identities are placeholders only for
  shared current membership, unexpired archive membership, direct receipts,
  or identities referenced by accessible historical chat. Unavailable
  identities return the same 404. Account deletion and receipt-history APIs
  still need implementation and must preserve authorized historical identity.
- Dev host PUT/DELETE clears restrictions on promotion, cancels the demoted
  host's pending invitations and requires another active host for demotion.
  Attributed host-change chat resolves current usernames at read time and
  shows `Deleted user` for tombstones. Account deletion, freeze recovery and a
  realtime table-view invalidation remain unimplemented.
- Reports, feedback and admin operations are outside
  `AppCommands`. Dev `POST /feedback` submits feedback but does not implement
  rate limiting, moderation reads or personal-data cleanup; links stored before
  V18 are not revalidated. Dev `GET /admin/health` reports PostgreSQL and Redis
  status without connection details; Kafka is `NOT_CHECKED` and no latency or
  error metrics exist. Admin flag and strike storage does not enforce
  authorization, automatic freezes, session revocation or deletion cleanup.
  Report submission, moderation reads, report-status updates and most admin
  operations remain incomplete.
- Dev `GET /me/debt-cap` reads the saved setting and default. Dev
  `GET /me/debt` reads current table exposure and open receipt nets in one
  database snapshot. Dev `PUT /me/debt-cap` requires zero debt and whole-point
  limits; retry protection preserves a committed outcome. Proposal, pick and
  lock enforce caps; debt reads have no realtime invalidation.
- Dev `GET`/`PUT /me/invite-preferences` read or replace the caller's policy.
  Defaults are friends `open` and following `request`; updates cancel
  ineligible pending invitations. Dev invitation responses enforce current
  host, follow, block and preference rules; acceptance adds the member and a
  system chat message. Losing eligibility cancels a pending invitation
  permanently. Table member requests can create invitations.
- Dev follow/unfollow and block/unblock mutations use pair locking and the
  retry guard. Blocks remove follows and cancel pending invitations in both
  directions. Blocked follow attempts return the same 404 as unavailable
  users, and frozen users remain followable. Unblocking does not restore
  relationships; chat window reads filter blocked text and GIF authors.
- Dev bet list and detail reads require a current player session and membership
  in the non-archived table. Active reads expose only the caller's participation
  and selection plus the aggregate pick count; locked reads reveal picks. Current
  pick storage is plaintext and accessible to an operator with infrastructure access.
- HTTP idempotency has a draft minimum 24-hour per-user guarantee, independent
  of event-consumer markers. The dev invite-preference PUT, debt-cap PUT,
  feedback POST, invitation response
  POST, follow/block POST/DELETE, reaction PUT/DELETE, chat POST, last-read PUT
  member-permission PUT, remainder PATCH, host PUT/DELETE, bet proposal,
  pick/clear and result POSTs, points-settlement POST, and receipt, group and
  review-acknowledgment POSTs implement it
  using a PostgreSQL transaction, keyed fingerprints and bodyless 201 or 204
  outcomes, plus a resource reference for the original chat-creation response.
  The guard checks the current account and session before
  replay and fails closed if its key changes. It never prunes records; safe
  commit-relative pruning, account-deletion behavior, response retention for
  other operations, and key rotation/recovery remain to be designed. Other
  mutations do not have this protection. After the guarantee, clients must
  reconcile state instead of retrying an uncertain operation blindly. The
  frontend keeps an uncertain action's key in per-user local storage, shared by
  the browser's tabs, and refuses to retry it after 23 hours; other devices
  reconcile from reads.
- Prototype-only reducer actions such as local `tick`, `gameResult`, and
  `loadBlocks` are not public player commands. Server-side clock and feed events
  drive lock and game result transitions.
- Bet outcome resolution (`SETTLED`) is distinct from the host's later
  settlement of table points (`pointsSettled`); clients need both for balances
  and Quick Pick wash eligibility.
- Dev table deletion snapshots members, disconnects subscriptions, cancels
  pending invitations and retains a read-only chat archive for those members
  for 90 days; afterwards the archive is hidden but its data remains. It
  preserves receipts and Quick Pick copies. It requires
  settlement of all resolved non-4fun bet points. Receipt actions and zero-net
  settlement exist internally; retained-data cleanup remains unimplemented.
- Realtime: STOMP at `/ws` with per-user destinations
  `/user/queue/tables/{id}`, `/user/queue/tables/{id}/bets`,
  `/user/queue/tables/{id}/chat`, `/user/queue/settlements`, `/user/queue/me`
  and `/user/queue/ticker`. Ordering, replay and gap detection beyond
  refetch-on-reconnect remain open.

## Phase 0 exit checklist

- [ ] Describe every command and read surface in OpenAPI 3.1, with schemas and
      errors; reconcile the table above with the client and current decisions.
- [x] Specify and review authenticated realtime destinations and event shapes.
- [ ] Verify sports-feed chronology and coverage before auto-settlement.
