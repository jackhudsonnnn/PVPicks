# Domain rules and test inventory — working reference

Current guides, the roadmap, and accepted decisions take precedence over the
archived v3 plan. The source paths below identify existing
frontend tests to review; proposed JUnit names are targets, not implemented
tests. `backend-test-map.md` enumerates individual frontend declarations with
proposed names; the crosswalk still needs case-by-case applicability review.

| Area | Invariants to preserve | Existing frontend tests | Example JUnit targets |
|---|---|---|---|
| Payout | Integer cents; losers fund winners; zero sum; No guess excluded; wash for insufficient pot, same side, unpicked winner or invalid outcome. Host/proposer can receive remainder even when not a winner; earliest/random distribute to winners. | `bets/payout.test.ts`, `bets/ledger.test.ts` | `splitsLoserPotInCents`, `washWhenNoWinner`, `remainderCanGoToNonParticipant`, `settlementIsZeroSum` |
| Lifecycle | ACTIVE accepts picks; only the caller's own pick and aggregate counts are visible until lock, not other participants or their picks. Lock moves to PENDING and snapshots remainder rule. Game outcomes come only from the feed; authorized people settle U2Pick. | `store/reducer.test.ts`, `bets/picks.test.ts`, `bets/u2pick.test.ts` | `hideOtherPicksUntilLock`, `snapshotRemainderAtLock`, `rejectManualGameResult` |
| Bets | Wagers are 0.10–10.00 points except 4fun; validate each mode and overtime behavior; 4fun creates no tracked pot. | `bets/wager.test.ts`, `bets/modes.test.ts`, `bets/overtime.test.ts`, `bets/info.test.ts` | `rejectOutOfRangeWager`, `fourFunDoesNotPostLedger`, `validateModeAndOvertime` |
| Balances and cap | Settle is unresolved-settlement deltas; True subtracts pending wagers. Debt sums negative table True balances and negative open person nets without positive offsets. A cap cannot change with debt; enforce on propose, pick and lock; 4fun is exempt. | `table/scenarios.test.ts`, `settings/debt-cap.test.ts` | `trueIncludesPendingWagers`, `positiveBalancesDoNotOffsetDebt`, `overCapPickPassesAtLock` |
| Settle-ups | Host creates separate settlement lines; sent remains outstanding until confirmed; forgiveness is for the whole amount; stale in-flight pair nets reset and notify; batch actions are atomic. | `table/settlements.test.ts`, `social/settle-receipts.test.ts`, `social/settle-groups.test.ts`, `social/settle-changes.test.ts` | `sentRemainsOutstanding`, `repeatedSettlementsKeepSeparateReceipts`, `changedNetResetsInFlightReceipt` |
| Quick Picks | Exact copies do not chain; source results propagate; a subscriber host may wash their copy; existing copies persist when subscriptions change. Every table retains a six-character code; hosts may disable sharing. | `table/quick-picks.test.ts`, `store/reducer.test.ts` | `copyDoesNotFanOut`, `hostWashStopsSourcePropagation`, `copiesSurviveUnsubscribe` |
| Identity and deletion | Case-insensitive unique username, 3–15 lowercase alphanumeric/underscore characters; deletion removes personal data, preserves ledger identity as Deleted user and hands off hosted tables. | `auth/local-accounts.test.ts`, `settings/profile.test.ts`, `settings/deletion.test.ts` | `usernameIsUniqueIgnoringCase`, `deletedUserRetainsLedger`, `deletionHandsOffHostedTables` |
| Social and safety | Mutual follows are friends; enforce invite preferences and blocks; reports reach admin; three strikes freeze the account. | `social/follows.test.ts`, `social/invites.test.ts`, `social/safety.test.ts`, `admin/admin-pages.test.ts` | `mutualFollowCreatesFriendship`, `blockPreventsInvites`, `thirdStrikeFreezesAccount` |
| Chat and notifications | Authorize table access, filter blocked users, preserve message ordering and cursor paging; one reaction per user/message. Notifications are inbox-only, filtered by preferences and cursor-paged. | `table/chat-window.test.ts`, `table/reactions.test.ts`, `table/unread.test.ts`, `notifications/events.test.ts`, `notifications/history.test.ts` | `reactionReplacesPriorReaction`, `cursorPagingRemainsStable`, `blockedChatDoesNotNotify` |

Paths in the table are relative to `client/src/features/`. Review the
individual case-by-case mapping before declaring the Phase 0 exit criteria met.
Some frontend tests exercise presentation rather than backend rules and should
remain frontend tests rather than being artificially ported.

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
table locking and recheck fresh state. The bets permission is stored but its
enforcement remains unimplemented.

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
enforce this coordination; bet proposal and locking are not implemented.

For table betting-setting changes, lock the actor and session, active table,
and membership in that order. Only hosts can set `hostsOnlyBets` and
`u2pickEnabled`. A matching retry rechecks host access and returns the current
table without reapplying old values. An unchanged value is a no-op; each actual
switch change posts a neutral system chat notice, with table and chat outbox
events in the same transaction. Existing bets are unchanged, including Active
and Pending bets. Future proposal and Quick Pick fan-out/backfill transactions
must lock the same table and recheck these switches after locking; those
transactions do not yet exist.

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
changes write an internal profile invalidation but no notification. Attributed
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
or the required transactional outbox producer. The bets module has an
independently tested pure payout calculator; no service invokes it or writes
its results to the ledger.

The calculator accepts participants in first-participation order, with no choice
for No guess, and applies the lock-time remainder snapshot. Host and proposer
rules require their recipient rather than falling back; random uses the
prototype-compatible deterministic shuffle seeded by the bet ID. This is
pseudorandom, not an unpredictable draw. It returns nonzero ledger deltas in
integer cents, or a wash reason; 4fun produces no ledger deltas. A future
service must provide validated, authoritative picks and snapshots, protect the
bet ID and inputs from manipulation, and atomically persist the outcome,
ledger and outbox event. The calculator does not validate authorization or
implement the sealed-pick protocol.

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
amounts outside the stored integer range. The caller must select only resolved,
unsettled, non-4fun bets from the correct table, including their complete
ledger deltas. It must authorize the host and coordinate selection, settlement
links, bet state, receipts, chat and outbox writes atomically; none of those
services exists yet.

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
receipt actions and account deletion are not yet implemented.

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
Sending does not yet create notifications, enforce request-size or rate limits,
or deliver events.

Reaction mutations lock the actor and session before the table, membership and
message; blocking also locks the actor, serializing the visibility check.
Future membership, archive, message, author-deletion and moderation writers
must coordinate compatible user-before-table locking and recheck state after
locking; deletion must also prevent a visible author from becoming deleted
concurrently with the reaction. Storage does not enforce this protocol.

The user chose chat-read-position synchronization for inbox notifications:
advancing a table's read position marks that user's chat notifications for
messages through it as read across sessions, even when presently hidden. A
fresh request for an older position synchronizes through the current position;
a matching retry does not reapply. V20 ensures a new notification for an
already-read message starts read. Insertion and advancement serialize on the
recipient user at READ COMMITTED isolation; future writers must preserve this
order and isolation or independently ensure a fresh snapshot after locking.
V20 does not change pre-existing inbox rows. Marking the inbox read does not
advance chat read positions.
The prototype's newest 200 items and 15-item pages are the draft retention and
pagination limits. Storage alone does not enforce the cap, visibility, block
filtering or durable event deduplication after an item is cleared or evicted.
The user chose to hide table notifications after normal table access ends,
while preserving separately authorized settlement notifications. Hidden
notifications may reappear if access returns. For the delegated block policy,
follow the prototype: hide the caller's blocked users' chat notifications and
hide invitations blocked in either direction, without hiding bet or settlement
notifications merely because of a block. Before inbox reads can implement
this safely, invitation and settlement notifications need source references
and authorization rules that distinguish pending invitations, direct joins,
individual receipts and person-level net changes. Existing stored text alone
cannot establish those permissions.
The dev preference read returns the documented defaults when no preference row
exists and includes the user's saved muted-table IDs. The dev preference update
requires current membership in a non-archived table for each newly added mute;
existing stale mutes may be retained or removed, but cannot be re-added without
membership once removed. Deletion cleanup is not implemented, so reads may
include old table IDs. The update stores preferences and mutes atomically; it
does not deliver notifications or emit realtime invalidations.

The per-user idempotency guard protects table creation and membership requests,
table profile/host/permission/remainder/betting-setting changes, settings updates, inbox mark-read and
clear, feedback, invitation responses, reactions, chat sending and last-read,
and follow/block mutations. It rechecks active account and session, serializes
the update and retry claim, and fingerprints canonical settings and sorted mute
IDs using a separate keyed HMAC. Matching retries do not reapply the update;
changed requests conflict, and invalid new mutes roll back the claim. It stores
neither raw keys nor request bodies. Records are not pruned; commit-relative
pruning, account-deletion cleanup, key rotation and recovery require design.
The guard also stores resource references and, for membership operations,
the original per-person outcomes. Fingerprinting is not sealed-pick protection.

The dev inbox mark-read operation marks the caller's existing unread
notifications read without changing chat read positions. A matching retry
returns the original outcome without marking notifications that arrived later;
a fresh key marks the newer entries. Notification inserts and this update lock
the recipient user first, so an insertion and mark-read are serialized.
The dev inbox clear operation deletes only the caller's existing entries,
without advancing chat read positions. Retrying the same key leaves later
arrivals intact; a fresh key clears them. Clear and insertion also serialize
on the recipient user. Future notification and chat-read writers must maintain
a compatible lock order. Removing inbox rows also removes their row-level
deduplication keys; future event consumers must atomically record durable
processed-event markers with notification effects, including when an item is
later cleared or evicted. Inbox retrieval, delivery, cap enforcement and
realtime invalidations remain unimplemented.

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
pot; clearing before joining does not create a participant. Because v3 stores
No guess as no pick row, participation must be retained separately from picks.
The draft bet view exposes No guess participant IDs only after lock.
The caller can always distinguish their own `not_joined`, `no_guess` and
`picked` states without seeing other Active participants.
The user confirmed that admin audit views must also hide other users' Active
participation and picks, including user-specific participation-derived
counts, until the bet locks. An admin who is a table member has the same
own-pick and table-wide aggregate visibility as any other member. This is an
authorization rule, not yet cryptographic protection from the operator.
The preferred stronger-privacy direction is an automatically revealed,
client-sealed pick with an independently controlled release authority, so a
device going offline after an accepted submission does not cancel the pick.
This is a design target, not a guarantee or implemented protocol: trust in the
client code and release authority, deadline verification, invalid ciphertext,
recovery, same-user multi-device access and service outages still need design
and independent security review. The draft plaintext pick API and V7 storage
do not provide operator-blind privacy.
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
continuity and a consistent lock-time selection. These behaviors are not yet
implemented.

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
authorization design; account deletion and receipt history do not yet exist.
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
notification. Member removal cancels pending invitations involving the target;
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
records. The outbox records events but does not deliver notifications; future
consumers must recheck invitation eligibility before exposing or delivering an
event that may have been cancelled after it was written.
