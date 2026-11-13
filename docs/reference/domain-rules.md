# Domain rules and test inventory — working reference

Current guides, the roadmap, and accepted decisions describe the design.
The source paths below identify frontend tests; proposed JUnit names are
examples rather than a list of implemented tests.

| Area | Invariants to preserve | Existing frontend tests | Example JUnit targets |
|---|---|---|---|
| Payout | Integer cents; losers fund winners; zero sum; No guess excluded; wash for insufficient pot, same side, unpicked winner or invalid outcome. Host/proposer can receive remainder even when not a winner; earliest/random distribute to winners. | `bets/payout.test.ts`, `bets/ledger.test.ts` | `splitsLoserPotInCents`, `washWhenNoWinner`, `remainderCanGoToNonParticipant`, `settlementIsZeroSum` |
| Lifecycle | ACTIVE accepts picks; only the caller's own pick and aggregate counts are visible until lock, not other participants or their picks. Lock moves to PENDING and snapshots remainder rule. Game outcomes come only from the feed; authorized people settle U2Pick. | `store/reducer.test.ts`, `bets/picks.test.ts`, `bets/u2pick.test.ts` | `hideOtherPicksUntilLock`, `snapshotRemainderAtLock`, `rejectManualGameResult` |
| Bets | Wagers are 0.10–10.00 points except 4fun; validate each mode and overtime behavior; 4fun creates no tracked pot. | `bets/wager.test.ts`, `bets/modes.test.ts`, `bets/overtime.test.ts`, `bets/info.test.ts` | `rejectOutOfRangeWager`, `fourFunDoesNotPostLedger`, `validateModeAndOvertime` |
| Balances and cap | Settle is unresolved-settlement deltas; True subtracts pending wagers. Debt sums negative table True balances and negative open person nets without positive offsets. A cap cannot change with debt; enforce on propose, pick and lock; 4fun is exempt. | `table/scenarios.test.ts`, `settings/debt-cap.test.ts` | `trueIncludesPendingWagers`, `positiveBalancesDoNotOffsetDebt`, `overCapPickPassesAtLock` |
| Settle-ups | Host creates separate settlement lines; sent remains outstanding until confirmed; forgiveness is for the whole amount; stale in-flight pair nets reset; each person must review their own warning before sending or asking for forgiveness; batch actions are atomic. | `table/settlements.test.ts`, `social/settle-receipts.test.ts`, `social/settle-groups.test.ts`, `social/settle-changes.test.ts` | `sentRemainsOutstanding`, `repeatedSettlementsKeepSeparateReceipts`, `changedNetResetsInFlightReceipt` |
| Quick Picks | Exact copies do not chain; source results propagate; a subscriber host may wash their copy; existing copies persist when subscriptions change. Every table retains a six-character code; hosts may disable sharing. | `table/quick-picks.test.ts`, `store/reducer.test.ts` | `copyDoesNotFanOut`, `hostWashStopsSourcePropagation`, `copiesSurviveUnsubscribe` |
| Identity and deletion | Case-insensitive unique username, 3–15 lowercase alphanumeric/underscore characters; deletion removes personal data, preserves ledger identity as Deleted user and hands off hosted tables. | `auth/local-accounts.test.ts`, `settings/profile.test.ts`, `settings/deletion.test.ts` | `usernameIsUniqueIgnoringCase`, `deletedUserRetainsLedger`, `deletionHandsOffHostedTables` |
| Social and safety | Mutual follows are friends; enforce invite preferences and blocks; reports reach admin; three strikes freeze the account. | `social/follows.test.ts`, `social/invites.test.ts`, `social/safety.test.ts`, `admin/admin-pages.test.ts` | `mutualFollowCreatesFriendship`, `blockPreventsInvites`, `thirdStrikeFreezesAccount` |
| Chat | Authorize table access, filter blocked users, preserve message ordering and cursor paging; one reaction per user/message. | `table/chat-window.test.ts`, `table/reactions.test.ts`, `table/unread.test.ts` | `reactionReplacesPriorReaction`, `cursorPagingRemainsStable` |

Paths in the table are relative to `client/src/features/`. Review the
backend test guidance before declaring the contract complete.
Some frontend tests exercise presentation rather than backend rules and should
remain frontend tests rather than being artificially ported.

## Proposal, pick and lock

U2Pick, the first bet mode, is available end to end over HTTP and in the
frontend: proposal, pick/clear, deadline locking by the dev lock worker,
member-called resolution, host points settlement and individual/group receipt
history and actions. Balance-change warnings are delivered privately through
the pending-review list that clients read; realtime delivery is future work.
Sports modes follow later and are not yet implemented.

Both mode families use the same pick, privacy, cap, lock, payout and points
settlement invariants. U2Pick validates custom questions and choices and uses
an authorized member-called result. Sports modes validate game-specific data,
baselines and applicable overtime rules, and only the feed can supply their
outcomes. Neither mode may bypass the shared transactional lifecycle.
The current proposal transaction handles U2Pick only. When sports proposal
validation is added, reuse or extract its common authorization, retry, debt-cap
and deadline mechanics; do not reuse U2Pick's custom definition or settler
rules for game data or feed results.
The user chose debt caps as the only spending limit: the prototype's separate
100-point virtual-balance checks on proposal and joining will not be enforced.

- **Propose:** authenticate a player, lock and recheck the account, session,
  table, membership and applicable betting settings. Validate the mode, wager,
  duration and debt cap, then atomically create an Active bet and its outbox
  event. The server assigns the deadline using database time and the requested
  whole-second window of 30–600 seconds. The proposer does not join automatically.
  For U2Pick, top-level question and choices are authoritative: the server
  normalizes `config.question` and `config.options` to those values, preserving
  other valid config fields. A retry requires current membership and betting
  permission, but changing host-only or U2Pick settings after creation does not
  prevent replay of a previously accepted proposal.
- **Pick or clear:** authorize the current member and betting permission;
  accept valid choices only while Active and before the server-side deadline.
  Serialize updates per bet, enforce the debt cap and preserve first
  participation order. Clearing after joining keeps a No guess participant;
  clearing before joining does not create one. Retry claims and changes must
  commit together. The internal transaction emits an aggregate-count
  invalidation only when the count changes; it does not emit another player's
  identity or selection, or signal a selection-only change.
- **Lock:** serialize with picks, membership and table-setting changes; recheck
  caps and apply the documented over-cap behavior. If a participant is frozen
  or lacks betting permission at lock, remove their pick and retain their
  No guess participation. Snapshot the remainder rule and, for Host, persist
  one randomly chosen current host. Commit the Pending transition, snapshots
  and outbox event together. A delayed lock worker must not extend the pick
  deadline; retries must not redraw the recipient. Settings, current hosts
  and participant eligibility are evaluated at actual lock processing time,
  not reconstructed at the deadline; outages can therefore affect the snapshot.
  The dev worker scans overdue Active bets on startup and after each
  five-second delay, ordered by deadline and ID in pages of 100 using a
  database-clock scan horizon. It continues past individual failures and
  retries overdue bets on later scans, including when a new picker appeared
  while locks were acquired. Each attempt has its own transaction and a
  two-second database lock timeout; competing workers use the same shared
  lock transaction and cannot commit the transition twice. Item failures are
  logged, while a scan-level failure is logged by the scheduler and retried.
  The dev profile enables the worker by default
  (`PVPICKS_BET_LOCK_WORKER_ENABLED=false` disables it), so starting the
  backend changes existing overdue bets in the connected database. Sports modes are logged and left Active
  until their feed-specific lifecycle is implemented. A scan is not a
  deadline guarantee; the pick transaction still rejects late changes.
- **Reads and recovery:** Active views expose only the caller's own state and
  aggregate counts, never other participants or picks. Admin audit views follow
  the same pre-lock restrictions. After lock, authorized views may reveal
  picks. Failed transactions leave no partial transition; an unavailable
  database must not be interpreted as an empty set of picks.
- **Resolve:** an eligible table member calls a Pending U2Pick according to
  its stored settler rule. Validate the outcome and atomically write the
  resulting Settled or Washed state, zero-sum ledger deltas and outbox event.
  4fun bets produce no ledger entries. An authorized retry must not resolve
  the bet twice. For the non-participants rule, only active player members
  count when deciding whether everyone is in the pot and hosts may call it.
  Frozen accounts do not prevent that fallback; No guess is outside the pot.
  The internal resolver locks and rechecks eligible membership, picked users
  and remainder recipients before calling `U2PickSettler`. A null winning
  choice explicitly washes; a non-null choice must match an available option.
  Until Quick Pick propagation is implemented, the resolver rejects originals
  with copies and does not allow direct resolution of a copy.
- **Settle points and receipts:** a current host settles the table's eligible
  resolved non-4fun bets, including a zero-net group, atomically recording
  settlement history, bet links and any payer/payee receipts. Resolution alone
  does not clear table deletion or member-removal guards; applicable points
  must also be settled. The settlement adapter rechecks current host
  access and returns current line statuses on an authorized retry. Sent receipts
  remain outstanding until confirmed.

Affected user rows must be locked in PostgreSQL UUID order before table and bet
rows, with fresh checks after locking. Integration tests need to cover deadline,
permission and cap boundaries, concurrent pick/lock and setting changes,
idempotent retries, privacy, permission revocation and frozen accounts, and
lock-time snapshot persistence. Resolution and settlement tests must cover
settler authorization, zero-sum ledger writes, duplicate requests, stale
receipt revisions and concurrent net changes. The worker has log-based error
visibility but no operational dashboard or alerting.

For table reads, the user chose to expose the Quick Pick code only to current
hosts. Hosts can see all disabled member permissions; other members can see
only their own. Current, non-archived table membership is required even for an
administrator or invitee. The dev read endpoints use one PostgreSQL statement
to check the session, account, membership and role while retrieving the view;
they return the same not-found response for absent, archived or inaccessible
tables. They do not expose bet participation or picks.

For member-permission changes, lock actor and target user rows in PostgreSQL
UUID order, then the actor's session, table and membership rows; check current
host authorization and the target's current non-host membership before retry
replay. Frozen targets are eligible, deleted targets are not. Hosts cannot be
limited. A retry never restores an earlier permission value after a later
change. Changes do not create system chat messages. The internal outbox event
contains only table and target IDs; future delivery must not expose the target
or permission change to other non-host members. Text, GIF and reaction writers
serialize on the target user and table before checking permission. Future
membership, host-role, archive and bet writers must use compatible user-before-
table locking and recheck fresh state. Internal proposal and pick transactions
enforce the bets permission.

For host promotion/demotion, lock actor and target user rows in PostgreSQL UUID
order, then the actor's session, active table and relevant memberships. The
user chose to require another `ACTIVE` host before a host steps down; a frozen
host does not satisfy this check, although a frozen member may be promoted.
The table lock serializes host changes. Future freeze, unfreeze, deletion and
membership writers must take their required user locks first and then lock all
affected tables in a consistent order before changing relevant status or
membership, with fresh READ COMMITTED checks. This protects the step-down
decision but a later freeze can still leave no active host; recovery is not
implemented and the schema does not enforce this coordination.

Promotion clears all member restrictions; demotion leaves those permissions
enabled and cancels that host's pending invitations for the table in the same
transaction. Existing accepted invitations and bet remainder snapshots remain
unchanged. A matching retry rechecks current host access and target membership
without reapplying, so a successful self-demotion retry returns 403 until the
caller is a host again. Host-change system messages store a constrained event
and subject ID, not a username; chat reads render the current username or
`Deleted user` from the tombstone. V22 does not repair legacy free-text system
messages, including invitation-acceptance messages, whose deletion cleanup
remains unfinished. Host and chat invalidations are internal outbox rows only.

For remainder changes, lock the actor and session, active table, and membership
in that order. Recheck host access before retry replay. A fresh change checks
for Active bets after acquiring the table lock and returns 409 without storing
its retry claim if any exist; an unchanged value is a successful no-op.
Pending bets retain their previously stored snapshots. Each actual change
creates a neutral system chat message and internal table/chat outbox events.
Future bet creation and status writers must acquire their required user locks,
then the same table lock before inspecting or writing bet state, using a fresh
READ COMMITTED statement snapshot after that lock. The schema alone does not
enforce this coordination; the internal U2Pick proposal and lock transactions
take the table lock, but their public workflow is not implemented.

For table betting-setting changes, lock the actor and session, active table,
and membership in that order. Only hosts can set `hostsOnlyBets` and
`u2pickEnabled`. A matching retry rechecks host access and returns the current
table without reapplying old values. An unchanged value is a no-op; each actual
switch change posts a neutral system chat notice, with table and chat outbox
events in the same transaction. Existing bets are unchanged, including Active
and Pending bets. Internal U2Pick proposal locks the table and rechecks these
switches. Future Quick Pick fan-out/backfill transactions must do the same.

For table profile changes, a current host may update name and/or emoji using
the same validation as creation. Lock the actor and session, active table, and
membership in that order. Matching retries recheck host access and return the
current table without reapplying. Unchanged values produce no events. An actual
change writes a neutral system chat notice without embedding the table name,
plus table and chat outbox events. Pending invitation reads reflect the current
name and emoji; future delivery must privately invalidate eligible invitees'
views while rechecking their access.

Dev personal-profile updates accept a username and/or hue. The username is
trimmed using ECMAScript whitespace and lowercased before applying the 3–15
ASCII letter, digit and underscore rule and database-backed uniqueness.
A rename releases the old username, changes future login and keeps existing
sessions active. An authenticated matching retry returns the current profile
without reapplying; a username conflict does not reserve the retry key. Actual
changes write an internal profile invalidation but no client delivery. Attributed
host-change messages use the current username; legacy free-text messages are
not rewritten. Login uses the same whitespace normalization.

For member removal, a current host may remove another non-host member, including
a frozen member; another host must first be demoted and self-removal is not
implemented. Block removal while any table bet is Active or Pending or any
resolved non-4fun bet awaits points settlement. This prevents membership removal
from hiding outstanding table debt under the current debt calculation. Failed
attempts use a distinct error code so a client can offer permissions instead.
Cancel pending invitations involving the removed user, retain the chat read
position and existing bets, ledger and receipts, and emit a neutral chat notice.
An authorized matching retry does not remove a subsequently readded member.
Future bet and settlement writers must cooperate with the user/table locking
protocol so the gate cannot race those changes.

Ledger deltas for a bet must be inserted in one database transaction whose
entries balance to zero. Corrections, including a resolved Quick Pick wash,
append compensating entries instead of changing or deleting old entries.
V8 serializes ledger writers by bet and checks the sum at commit; transactions
touching multiple bets must lock them in a consistent order and retry deadlocks.
This storage check does not implement settlement authorization, idempotency
or the required transactional outbox producer. The internal shared resolution
writer invokes the bets module's pure payout calculator and posts its results;
`POST /bets/{id}/settle` exposes it.

The calculator accepts participants in first-participation order, with no choice
for No guess, and applies the lock-time remainder snapshot. Host and proposer
rules require their recipient rather than falling back; random uses the
prototype-compatible deterministic shuffle seeded by the bet ID. This is
pseudorandom, not an unpredictable draw. It returns nonzero ledger deltas in
integer cents, or a wash reason; 4fun produces no ledger deltas. A future
caller must provide validated, authoritative picks and snapshots, protect the
bet ID and inputs from manipulation, and authorize the result. The internal
writer atomically persists the outcome, ledger and outbox event. The calculator
does not validate authorization.

Debt-cap storage permits a nonnegative integer-cent limit or NULL for off; an
absent row also represents the default off state. The future service must
calculate debt and authorize cap changes atomically, not infer that a stored
cap alone enforces exposure. Settlement records and receipt identities are
retained separately for each settlement; receipt status and revision remain
mutable. The service must atomically keep settlement-bet links consistent
with the bet's points-settled timestamp and enforce receipt authorization,
revision checks, cooldowns and group net reconciliation.
The dev debt-cap read returns the saved value or NULL for either off state.
The retry-safe dev update accepts NULL or a nonnegative whole-point amount in
cents up to 2147483600. It rejects every fresh update while debt is nonzero,
including an unchanged value; a matching retry replays the prior success
without reapplying it. Previously stored non-whole-point caps remain readable.
This does not enforce the cap on bets.

The settlement module's pure debt calculator sums negative True balances per
table and negative open-receipt nets per counterparty, without offsetting
different tables or people. It checks exact cap boundaries and the 4fun
exemption. Its caller must supply current-member tables, only unsettled
non-4fun result deltas and pending wagers, and all open receipts (including
those retained after table deletion), grouped from the user's perspective.
Paid and forgiven receipts must be excluded; sent receipts remain open.
Dev `GET /me/debt` gathers those inputs from a single PostgreSQL snapshot,
including corrections on unsettled results. It excludes active bets, No guess
and 4fun wagers, former or archived table memberships, and paid or forgiven
receipts; archived-table open receipts remain included. The endpoint does not
serialize subsequent changes. The cap update locks the user before reading
the debt and writes the cap in that transaction. Every future writer that can
change a user's debt must first lock all affected user rows in PostgreSQL UUID
order, before sessions, tables, bets and receipts, and must read current state
after acquiring those locks. This includes membership and archive changes,
bet locks, ledger corrections, settlement and receipt transitions. Existing
direct SQL storage alone does not enforce that protocol; no future debt
mutation should be deployed until it cooperates.

The settlement module has a pure calculator that nets supplied bet deltas and
produces positive-amount receipts routed through the settling host. It checks
each supplied bet is zero-sum, rejects duplicate or absent bets, and rejects
final amounts outside the stored integer range. Exact per-bet aggregates may
exceed that range before later bets offset them. The internal shared host
settlement transaction selects resolved, unsettled, non-4fun bets with all
ledger corrections, checks current host authorization, and atomically writes
the settlement, bet links and timestamps, receipts, static chat and outbox.
It locks initially discovered ledger users and the actor before the table and
bets, and fails retryably if a newly relevant user appears. Matching retries
recheck current host authorization and return the original settlement ID.
No eligible bets and unrepresentable totals fail without retaining the
idempotency claim or partial writes.

When a new settlement changes an open person net with sent or forgiveness-
requested receipts, the internal transaction returns those open claims to
unpaid, increments their revisions and records a private pair-net-change
outbox event with before/after amounts. Closed receipts are unchanged. The
outbox is not automatically delivered. Any future consumer must restrict
settlement data to the two named parties. The frontend warning-opening
interaction remains a client concern, not a server concurrency guard, and is
local to the browser. Internal receipt history is limited
to direct parties, including archived-table and completed records. Individual
actions require the current line revision and payer/payee authority; they
never invalidate other in-flight receipts. A matching retry rechecks party
and action authority and returns the current receipt without reapplying. The
history adapter returns an empty list for unrelated or unknown users,
without disclosing whether they exist.

Internal person-group reads net open lines with exact arithmetic, including
opposing offsets and archived-table receipts. Their opaque revision includes
every open line ID and revision. The group-list adapter returns an
explicit 409 rather than truncating any net outside the signed 64-bit API range;
individual receipt history remains accessible. The pending-review list
independently discovers the caller's unreviewed warnings, including after
closure and when a group net is out of range; it does not deliver events or
expose the other party's review state. Group actions lock both parties in PostgreSQL
UUID order, reject stale revisions and validate current net payer/payee
authority once before updating all open lines atomically. Either party may
confirm a zero-net group. Paid and forgiven history stays visible but does
not affect the net. Matching group retries require a retained direct receipt
relationship and do not reapply the action. Fresh declined forgiveness
requests cannot be repeated for 24 hours, measured by the database clock.
The prototype permits an individual sent claim to coexist with an open
forgiveness request: `forgive_requested_at` tracks that request independently
of `status`, and a dispute restores the open request. A group sent, paid,
unpaid or forgiven action clears requests and cooldowns across its lines;
group ask/decline updates all requests while preserving sent offset claims.
Reconciliation resets both types of in-flight claim on a changed net.
Receipt mutation events remain private outbox records only. Pair-change events
may also be materialized by the internal consumer described above. Future
consumers must restrict them to the two parties. Realtime delivery of these
events remains future work; clients discover warnings through the review list.

Table deletion is a host action blocked while any bet is ACTIVE or PENDING or
any resolved non-4fun bet has not had its points settled. Washed and 4fun
bets do not require financial settlement. A host must be able to settle
eligible resolved bets even when their combined balance is zero for every
person: record the settlement and mark and link the bets atomically, without
creating zero-amount receipts. This avoids trapping a table when results cancel
out; the prototype currently disables that zero-net action.

Dev deletion removes the table from normal active-table views and disables
normal mutations. `GET /tables/archives` lists it for the active users who
were members at deletion; the existing chat window endpoint grants them
read-only access with current block filtering. Former members cannot access it.
Listing and chat access expire 90 days (2160 hours) after deletion. Expiry
hides the archive but does not delete retained data; a separate deletion
policy and account-deletion implementation remain future work. Unpaid
receipts continue to count in debt; receipt-history and action APIs are not
implemented. Retained financial history is not a promise of indefinite
personal-data retention.

Deletion disconnects Quick Pick subscriptions involving the table and
preserves existing copies and financial records. It cancels pending invitations
and snapshots membership atomically. The service locks member users in UUID
order before locking the table, and rejects a concurrent membership change.
Future bet, settlement, subscription and event writers must use compatible
locking and preserve committed source-result propagation. Outbox delivery,
public receipt actions and account deletion are not yet implemented.

Chat order is assigned after locking the table, so concurrent committed
messages have stable per-table order even if creation timestamps coincide or
the clock changes. Read positions store that order independently of message
rows, so deleting a read message does not incorrectly mark newer messages as
read. Dev last-read GET uses a single snapshot to enforce current membership
and visibility and exclude own and blocked text/GIF messages from unread
calculations. PUT requires a currently visible message and moves the position
forward only. Sequence values are internal, not cursors to expose directly to
clients. Dev chat window reads use the same visibility predicate and one
snapshot, returning up to 30 latest messages, up to 30 around a visible message
ID (five before and 25 from it onward), or up to 25 before/after an opaque
cursor. Every nonempty window provides a next cursor even at the current end,
so clients can retain it for later arrivals; empty windows return null cursors.
Cursors are encrypted, scoped to user and table and remain valid if a boundary
message is deleted. They use a domain-separated key derived from the dev
idempotency secret, so replacing that secret invalidates them. Bet projections
contain no picks; settlement projections contain only static parties and
amounts. Reactions from deleted users are hidden; blocked users' reactions on
otherwise visible messages remain visible, matching the prototype. Archive
members can read these windows for 90 days after deletion; last-read endpoints
remain restricted to active tables.

Dev reaction PUT replaces the caller's single reaction on a visible text, GIF
or bet message; DELETE removes it if present. A current, non-archived table
membership and react permission are required. The caller's outgoing blocks hide
the blocked author's text and GIF messages, but not bet cards. System and
settlement messages are not reactable. PUT accepts exactly one RGI emoji
sequence according to ICU4J 76.1 (Emoji 16.0), not arbitrary text or newer
sequences. Matching retries recheck access without reapplying; changed rows
write a table-only internal outbox invalidation. Realtime delivery does not
exist yet.

Dev chat sending requires current table membership and the corresponding text
or GIF permission. The user delegated the text limit; it is 1–2000 Unicode
code points after prototype-compatible trimming. The user selected the
prototype GIF catalog only; its accepted IDs must be updated with that catalog.
A send atomically stores a message, internal outbox event and retry claim.
Matching retries recheck current access, permission and unchanged message content,
then return the original 201 message without later reactions. If the message
was removed or changed, replay returns 404 and does not recreate it. The retry record
stores its UUID, not a duplicate message body, and reconstructs the original
response from the matching request and immutable creation time. Future message
editing, moderation and deletion must preserve authorization and replay
semantics; a redacted or inaccessible message must not be exposed by replay.
Sending does not yet enforce request-size or rate limits or deliver events.

Reaction mutations lock the actor and session before the table, membership and
message; blocking also locks the actor, serializing the visibility check.
Future membership, archive, message, author-deletion and moderation writers
must coordinate compatible user-before-table locking and recheck state after
locking; deletion must also prevent a visible author from becoming deleted
concurrently with the reaction. Storage does not enforce this protocol.

Chat read positions advance monotonically to visible messages. Matching
retries recheck access and visibility without reapplying the update.

The per-user idempotency guard protects table creation and membership requests,
table profile/host/permission/remainder/betting-setting changes, settings updates,
feedback, invitation responses, reactions, chat sending and last-read,
and follow/block mutations. It rechecks active account and session, serializes
the update and retry claim, and fingerprints canonical requests using a separate
keyed HMAC. Matching retries do not reapply the update;
changed requests conflict. It stores
neither raw keys nor request bodies. Records are not pruned; commit-relative
pruning, account-deletion cleanup, key rotation and recovery require design.
The guard also stores resource references and, for membership operations,
the original per-person outcomes.

Feedback accepts the five prototype categories, text of 10–1000 UTF-16 code
units after trimming and an optional HTTP(S) link without whitespace, matching
the prototype's JavaScript length check. A blank trimmed link is absent. V12
checks 5–1000 database characters to avoid rejecting valid supplementary
characters. The dev submission endpoint normalizes JavaScript whitespace,
rejects NUL and unpaired surrogates that PostgreSQL cannot safely store, and
atomically commits feedback with its 201 retry outcome. V18 aligns the link
constraint for new writes with JavaScript whitespace; legacy rows are retained
without validation and require review. Feedback may contain personal
information; account deletion must handle it and cannot merely tombstone the
user while leaving identifiable text behind. Moderation access, rate limiting,
retention and personal-data cleanup remain unimplemented.

Admin flag and strike rows default to unflagged and zero strikes. An absent row
has the same draft state. V13 limits stored strikes to 0–3 but does not freeze
the account or record an action. Future services must serialize actions,
authorize administrators, atomically record changes and revoke sessions on
freeze. Clearing strikes does not unfreeze; explicit unfreeze at three strikes
is allowed, and a later strike freezes again. Notes and flags are personal
moderation data requiring restricted access and deletion/retention policies.

Clearing an existing pick keeps that player as a No guess participant outside the
pot; clearing before joining does not create a participant. Because the backend stores
No guess as no pick row, participation must be retained separately from picks.
The draft bet view exposes No guess participant IDs only after lock.
The caller can always distinguish their own `not_joined`, `no_guess` and
`picked` states without seeing other Active participants.
Admin audit views must also hide Active participation and picks, including
user-specific participation-derived counts, until the bet locks. Admin
accounts cannot use player APIs or participate in bets. The existing schema
stores picks in plaintext; an operator with infrastructure access can inspect
or modify stored data. Pick authorization and lifecycle services do not yet
exist.
The proposer is visible as the bet's author but is not automatically a
participant. This intentionally changes the prototype, which inserts the
proposer on No guess when a bet is created.
The prototype retains a participant's position when changing or clearing a
guess and choosing again. For the earliest-pick remainder rule, preserve that
first participation order instead of ordering by the latest pick timestamp.
Future pick transactions must serialize per bet so concurrent first picks
have a well-defined order; database sequence values alone do not establish
commit order.

Quick Pick subscription rows are directed; reciprocal subscriptions are allowed,
but a table cannot subscribe to itself and copies never fan out. Disabling
Quick Picks disconnects both subscriptions to this table and this table's
subscriptions to others. Disabling sharing or rotating a code disconnects
subscribers to the source. Existing copies survive these changes. Future
domain transactions must coordinate subscription, rotation, feature toggles,
backfill and new proposals so races do not create missed or duplicate copies.
The dev feature switch enables sharing with the existing code when turned on.
Current hosts can separately stop sharing or rotate the code; a rotation retry
returns the current code, without rotating again. Changes write neutral chat
and internal invalidations, not delivered events. Historical codes are not
permanently reserved; subscription authorization and safe code reuse require
design before subscription endpoints are implemented.

For the Host remainder rule, select one of the table's current hosts uniformly
at random when each bet locks and persist that recipient with the rule in the
same transaction. Use server-side randomness, not client input; retries after a
committed lock must reuse the persisted selection. The selected recipient need
not have participated and remains the recipient after later host removal or
account deletion (represented by the retained deleted-user identity). Do not
confuse this recipient with the host who later settles table points. If a
table has no host at lock, reject the transition and surface the invariant
violation instead of falling back to a winner. Table creation, host changes,
account deletion and locking must serialize as necessary to preserve host
continuity and a consistent lock-time selection. The internal lock transition
implements the snapshot; the optional worker currently schedules U2Pick only.

The dev relationships read returns only the caller's outgoing blocks, never
the list of people who blocked them. It filters follow rows across blocks in
either direction and excludes deleted identities; existing frozen-user
relations remain visible without exposing account status. Blocking serializes
both users, removes follows in both directions and cancels their pending
invitations atomically. Unblocking removes only the caller's block and cannot
restore old follows or invitations. The user chose to allow following frozen
users and to return the same not-found response for a blocked follow and an
unavailable user. A follow retry rechecks the block before replay.

Dev individual-user lookup allows a caller to resolve people they blocked,
even for mutual blocks, so a private block list can display names. If only the
other person blocked the caller, lookup returns the identity only when they
share a current, non-archived table or are direct parties to a receipt. Frozen
identities are returned without status. Deleted-user placeholders require
shared current membership, shared unexpired archive membership, a direct
receipt, or a reference in accessible bet, system-attribution or settlement
chat. The same 404 covers other deleted users, hidden blocks and missing IDs.
Deleted identities referenced only by future history APIs will need their own
authorization design; account deletion is not implemented.
Social transactions lock both user rows in PostgreSQL UUID order before the
session and relationship rows; future follow, block, invitation, deletion and
moderation writers must use compatible ordering. Account-deletion cleanup,
cross-session event delivery is not implemented.

The dev invitation read returns eligible pending invitations with the table
name and emoji, not full table access. It excludes archived tables, deleted
senders, existing memberships, blocks and invitations failing host, follow or
preference requirements. Frozen senders remain eligible. The recipient must
follow the sender, the sender must still be a host, and the recipient's
current friends preference (mutual follow) or following preference must not be
`closed`. Both `open` and `request` permit responding to an existing request.
Defaults are friends `open` and following `request`.

Losing eligibility permanently cancels a pending invitation; it is no longer
available to decline or restored when eligibility returns. Dev follow and
preference mutations cancel affected requests atomically; V19 cancels
pre-existing ineligible requests. Acceptance revalidates the policy and
atomically creates a membership, system chat line and internal outbox records;
decline only updates the invitation. Retries recheck visibility, and acceptance
replay requires current membership. The system chat line is not a user chat
client delivery. Member removal cancels pending invitations involving the target;
table deletion cancels all pending invitations. Host demotion cancels its own
pending invitations. Future account-deletion writers must enforce cancellation
using compatible locks. Account deletion and event delivery are not implemented.

Dev table creation and host member requests accept at most 100 unique IDs.
The creator is a host. For active recipients who follow the host, mutual follows
use their friends preference and one-way follows use following; `open` adds
immediately, `request` creates a pending invitation, and `closed` does neither.
Frozen, deleted, missing, blocked, already-member and already-invited recipients
all yield the same `not_added` outcome. Each request is atomic but processes
eligibility per person. Pending invitations are private to the invite flow and
are not announced in table chat; direct additions produce a neutral notice.
Transactions lock all existing requested users and the actor in PostgreSQL UUID
order before the session and table. Matching add-member retries recheck host
access and return original outcomes without repeating actions. Creation retries
return the original outcomes and current authorized table view; lost table
access returns 404. V23 stores the submitted IDs and outcomes in retry records;
retention and account-deletion cleanup remain unresolved, as with other retry
records. The outbox records events but does not deliver them; future
consumers must recheck invitation eligibility before exposing or delivering an
event that may have been cancelled after it was written.
