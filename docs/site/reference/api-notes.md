# API behavior notes

Per-endpoint behavior and known limits of the dev backend that the OpenAPI
file (`backend/api/openapi.yaml`) doesn't spell out: retries, visibility,
paging and what's still missing. The OpenAPI file is the contract; the domain
rules are the business rules. The [realtime contract](/reference/realtime-contract) covers the WebSocket
destinations.

## By area

- Authentication: the dev-only eight-hour cookie session, CSRF flow, login,
  logout and `/me`. Failed logins are throttled (429 `rate_limited` with
  `Retry-After`); the passwordless demo sign-in is not. In dev, `/dev/demo/accounts` and `/dev/demo/sign-in` sign
  in as any demo account without a password; like login, they ignore a stale
  session cookie. In dev, every `/dev/**` path (dev data and the demo) works
  without a session, so the Dev page works signed out. The security filter checks the current database role for
  `/admin` routes (moderation, players and site health). Accounts come from
  the admin: `POST /admin/players` creates one with a single-use setup code,
  `POST /admin/players/{id}/code` issues a new setup or reset code, and
  `POST /auth/setup` redeems it (sets the password, ends other sessions,
  signs in). `POST /auth/password` changes your own password. Codes last 24
  hours, are stored only as hashes and appear once; bad codes and wrong
  current passwords count toward the sign-in throttle. Production sign-up
  and refresh tokens are still to be designed; LAN TLS is Caddy's (see
  [LAN watch party](/lan)).
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
  `ticker.changed` on `/user/queue/ticker` invalidates it. Responses are
  cached in Redis for up to five seconds between feed changes. The ticker contains
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
  1–2000 Unicode code points and restricts GIF IDs to the catalog in `chat/gifs.json` (`GET /gifs`).
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
  Search is limited to 30 a minute per account.
- Dev individual-user lookup resolves outgoing blocks, including mutual
  blocks, and incoming-only blocks only for shared current table members or
  direct receipt parties. Deleted identities are placeholders only for
  shared current membership, unexpired archive membership, direct receipts,
  or identities referenced by accessible historical chat. Unavailable
  identities return the same 404. Deleted accounts keep their authorized
  historical identity as `Deleted user`.
- Dev host PUT/DELETE clears restrictions on promotion, cancels the demoted
  host's pending invitations and requires another active host for demotion.
  Attributed host-change chat resolves current usernames at read time and
  shows `Deleted user` for tombstones. When an account is frozen or deleted
  and it was a table's last host, the oldest active member is promoted (or the
  table is archived). A realtime table-view invalidation for host changes is
  still missing.
- Reports, feedback and admin: `POST /reports` (a daily limit per reporter)
  feeds the moderation queue: `GET /admin/reports`, `GET /admin/users/flagged`,
  dismiss or strike a report (the third strike freezes the account and ends
  its sessions), unfreeze, clear strikes. Repeated actions answer
  `already_handled`. `POST /feedback` is limited to 5 an hour per account, and has no moderation reads or
  personal-data cleanup, and links stored before V18 aren't revalidated.
  `GET /admin/health` reports PostgreSQL and Redis without connection details;
  Kafka is `NOT_CHECKED` and there are no latency or error metrics.
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
