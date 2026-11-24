# Domain rules and test inventory — working reference

Current guides, the roadmap, and accepted decisions describe the design.
The table lists the client and backend tests that cover each area (most rules
are server-only, so the backend tests are authoritative).

| Area | Invariants to preserve | Client tests | Backend tests |
|---|---|---|---|
| Payout | Integer cents; losers fund winners; zero sum; No guess excluded; wash for insufficient pot, same side, unpicked winner or invalid outcome. Leftover cents go one each to winners at random ([Payouts](#payouts)). | None (server only) | `PayoutCalculatorTest`, `BetResolutionIntegrationTest`, `LedgerStorageTest` |
| Lifecycle | ACTIVE accepts picks (and card-game joins and leaves) right up to lock, with no late-entry cutoff (owner decision, 2026-11-24); a resolved or washed bet can be resent: a new proposal with the same terms, validated like any other (owner decision, 2026-11-24); only the caller's own pick and aggregate counts are visible until lock, not other participants or their picks. Lock moves to PENDING. Game outcomes come from the feed unless a host overrides them (planned); authorized people settle U2Pick. | `bets/ticket-model.test.ts`, `bets/u2pick.test.ts`, `bets/bet-results.test.ts` | `BetLifecycleIntegrationTest`, `BetPickIntegrationTest`, `BetLockIntegrationTest`, `BetReadModelIntegrationTest` |
| Bets | Wagers are 0.10–10.00 points except 4fun; validate each mode and overtime behavior; 4fun creates no tracked pot. | `bets/wager.test.ts`, `bets/sports-model.test.ts`, `bets/betting.test.tsx` | `BetProposalIntegrationTest`, `SportsBetIntegrationTest`, `SportsOutcomeTest`, `PlayerOutcomeTest`, `ScorcererOutcomeTest`, `U2PickDefinitionTest` |
| Balances and cap | One balance per table: unsettled results minus points in play ([Balances](#balances)). Host settle moves only unsettled results. Debt sums negative table balances and negative open person nets without positive offsets. A cap cannot change with debt; enforce on propose, pick and lock; 4fun is exempt. | `tables/api.test.ts` (balances), `tables/table-page.test.tsx` (what-if), `preferences/preferences.test.tsx` (debt cap) | `MyBetsAndBalancesIntegrationTest`, `DebtCalculatorTest`, `DebtIntegrationTest` |
| Settle-ups | Host creates separate receipts per settlement; a settle closes chosen open receipts with one person at once, netted; the person down settles (`sent`: settled at once, rejectable by the other for 48 hours, still counted toward the points limit until then); the person up, or either side when even, marks as settled (`received`, final); no forgive; at most 3 sent settles per pair per rolling 24 hours; a changed balance must be reviewed before settling; cards are yellow, red, green, neutral. | `settlements/api.test.ts`, `settlements/settlements.test.tsx`, `settlements/ledger-model.test.ts`, `settlements/settlement-integration.test.tsx` | `SettleRulesTest`, `SettleIntegrationTest`, `PointsSettlementIntegrationTest`, `TableSettlementCalculatorTest`, `SchemaIntegrationTest` |
| Quick Picks | Exact copies do not chain; source results propagate; a subscriber host may wash or set a result on their copy, which then stops following the source; existing copies persist when subscriptions change. Every table retains a six-character code; hosts may disable sharing. | `tables/table-page.test.tsx` (sharing and codes) | `BetResolutionIntegrationTest`, `TableSettingsIntegrationTest`, `SchemaIntegrationTest` |
| Identity and deletion | Case-insensitive unique username, 3–15 lowercase alphanumeric/underscore characters; deletion removes personal data, preserves ledger identity as Deleted user and hands off hosted tables. | `preferences/profile-rules.test.ts`, `auth/api.test.ts` | `UserIntegrationTest`, `AccountLifecycleIntegrationTest`, `AdminDeletionIntegrationTest` |
| Social and safety | Mutual follows are friends (account-wide; table members are peers, see [Terms](/domain#terms)); enforce invite preferences and blocks; reports reach admin; three strikes freeze the account. | `tables/management.test.tsx`, `tables/table-page.test.tsx` (reports) | `SocialIntegrationTest`, `ReportsAndTomatoesIntegrationTest`, `AccountLifecycleIntegrationTest` |
| Membership limits | 100 open tables per account (deleted tables don't count), 500 members per table, 100 waiting requests per account; requests expire after 30 days. Enforced on create, on every add (open or request) and on accept. List reads carry summaries and member previews, never whole member lists. | `tables/api.test.ts`, `tables/management.test.tsx`, `tables/table-order.test.ts`, `tables/can-bet.test.ts` | `MembershipLimitsIntegrationTest`, `TableMembershipIntegrationTest` |
| Chat | Authorize table access, filter blocked users, preserve message ordering and cursor paging; one reaction per user/message; one changeable vote per user/poll, polls gated by the table switch and the polls permission. | `chat/model.test.ts`, `chat/reactions.test.ts`, `chat/chat.test.tsx` | `ChatIntegrationTest`, `ChatStorageTest`, `PollIntegrationTest`, `GifFavoritesIntegrationTest` |

Paths in the table are relative to `client/src/features/`. Review the
backend test guidance before declaring the contract complete.
Some frontend tests exercise presentation rather than backend rules and should
remain frontend tests rather than being artificially ported.

## Moderation and account lifecycle

Owner decisions, 2026-11-14:

- **Reports.** A player has at most one open report per person. Reporting
  someone again while it's open updates it (reason and note, and the attached
  message when one is given) instead of filing another, so one player can't
  flood the queue about one person. New reports are capped at 20 a rolling
  day per reporter, and report calls at 10 an hour (owner decision,
  2026-11-17).
- **Moderation queue.** Admins see reports (`GET /admin/reports`, 50 a page)
  with the reported message as it was when reported. They dismiss each one, or resolve
  it with a strike against the reported player. Strikes stop at three, and
  reaching three freezes the account (again after an unfreeze). Clearing
  strikes doesn't unfreeze, and unfreezing doesn't clear strikes. Admin
  actions are one-shot: repeating one answers `already_handled`.
- **Player audits** (owner decision, 2026-11-17). Admins can see everything a
  player did and wrote: tables, record, settle-ups, reports, and a keyword
  search over their messages, polls and proposed bets. Bets still Active are
  left out of every pick and participation count until they lock. Every audit
  opened and every search run is recorded in `admin_audit_log` with the admin,
  and the audit log is visible to every admin
  ([Admin dashboard](/admin#users)).
- **Feedback inbox.** Admins mark player feedback Done or New with a private
  note; Done records who and when.
- **Frozen accounts can't sign in.** Freezing ends every session at once.
  After a correct code (or an admin's correct password), sign-in answers 403
  `account_frozen`. Bets, picks
  and receipts are untouched. Hosted tables follow host recovery.
- **Account deletion** (`POST /me/delete`, confirmed by typing your own
  username; players have no password):
  - It's blocked until you're square: no Active or Pending bet you proposed or
    joined, no unsettled table points, and no open receipts.
  - It then removes personal data: profile, relationships, invite
    preferences, chat text, GIFs and polls, reactions, poll votes, read positions, starred GIFs, feedback,
    debt cap, memberships and archive access, invitations and tomatoes.
  - The account becomes "Deleted user", which bets, the ledger and receipts
    keep referencing. Moderation records (reports, strikes) are kept.
  - Hosted tables follow host recovery. Every session ends.
- `accounts` coordinates freezing and deletion. Each module removes its own
  data through its own API (`TableAccountChanges`, `ChatAccountCleanup`,
  `SocialAccountCleanup`, `FeedbackAccountCleanup`,
  `SettlementAccountChanges`; the checks are in `BetAccountChecks`).

### Abusive language filter

Owner decision, 2026-11-20. Slurs and hate speech are refused in everything
people write for others to see: chat messages, poll questions and options,
U2Pick questions and options, table names (new and renamed), usernames (your
own and players an admin creates). Swearing and trash talk stay allowed. A
match is refused with 400 `abusive_language`, "That has language that isn't
allowed here."; nothing is posted, no strike is given, and the chat box keeps
the text so it can be edited.

The terms are in `moderation/abusive-terms.txt` (backend resources), checked
by `common/AbusiveLanguage`. Before matching, text loses accents and invisible
characters, look-alike letters and symbol swaps become letters (n1gg@), and
runs of single letters are joined (f a g, n.i.g.g.e.r). Terms match whole
words, stretched (never with fewer letters, so "Niger" isn't caught) and with
a plural s or z; terms marked `*` also match inside run-together words.
Ordinary words that contain a term (raccoon, spiced, retardant, fagioli) pass.
Tests: `AbusiveLanguageTest`, `AbusiveLanguageIntegrationTest`.

### Admin account deletion

Owner decisions, 2026-11-18. The admin can delete any player outright
(`DELETE /admin/players/{playerId}`, **Delete account** on their card after
a warning), in every mode, without them being square:

- Every Active or Pending bet they proposed or picked in, at a current table,
  washes for everyone: "A player's account was deleted", with a chat line in
  that table naming the bet. Active bets lock first, so one that locking
  already washes (nobody picked, one side only) keeps that reason. Wagers
  come back; no points move. The count of washed bets is returned.
  (`bets.AccountBetWashes` locks every affected user in UUID order, then each
  bet's table and bet, like every other bet write.)
- Then the account is deleted exactly as above: personal data removed,
  memberships ended with host recovery, sessions revoked, code forgotten, and
  the username freed.
- Points already won or lost on settled bets, settle-ups and receipts stay,
  under "Deleted user". The host can still settle the table's points, and
  whoever is up on a receipt with them can close it with Mark as settled
  (tested in `AdminDeletionIntegrationTest`). The disruption the warning means
  is the washed bets, the player vanishing from their tables mid-game, and
  points with them that can only be closed one-sidedly.

## What the client may decide

The server owns every rule, and it tells the client what the viewer may do, so
the client never re-derives permissions:

- Bets carry `viewerCanCall` (call a Pending U2Pick's result under its settler
  rule, using the same `U2PickSettler` the result call enforces) and
  `viewerCanOverride` (a host, a sports bet, points not yet settled).
- Settles: the client works out which settle button a selection gets
  (Settle, or Mark as settled) from the same rules the server enforces
  (`SettleRules`), as a convenience only; the server refuses anything else.
  Each settle says whether the caller `canReject` it now.
- `GET /modes` says which bet modes accept proposals; the client only
  describes modes.
- `GET /gifs` serves the GIF library; sending accepts only its ids.

Reads are bounded: `GET /tables/{id}/bets` returns the live set (Active,
Pending, and resolved non-4fun bets whose points aren't settled), older bets
are read one at a time, and a member's bets at a table are paged
(`GET /tables/{id}/members/{userId}/bets`) with their record computed by the
server (`.../record`: resolved, non-4fun bets with a pick; washed and 4fun
bets don't count). Chat bet messages embed the bet's question, mode and status.
A receipt group whose net exceeds the 64-bit range still fails with
`group_net_out_of_range`; there is no separate fallback.

"No guess" is the only reserved choice text.

## Wash triggers

A wash ends a bet with no points moving. Triggers run at two moments, general
ones first, then the bet's mode. Hosts can still override a sports bet's
outcome (including a wash) until points are settled.

**At lock** (when picks close; the bet goes straight to Washed, never Pending).
Picks from accounts that can't bet (frozen, betting permission off, over their
debt cap) are dropped first. "No guess" isn't a pick.

| Trigger | Modes | Wash reason | Status |
|---|---|---|---|
| Nobody picked | All | "Nobody picked" | Implemented |
| Every pick is on one option (including a single picker) | All | "Everyone picked the same option" | Implemented |
| The player already passed the line before lock | Prop Hunt | "Line reached before picks locked" | Implemented |
| Either player already reached the target before lock | King Of The Hill | "Target reached before picks locked" | Implemented |
| The combined score already passed the line before lock (proposals at or below the live combined score are rejected) | Total Disaster | "Line reached before picks locked" | Implemented |

**At resolution** (when the result is known).

| Trigger | Modes | Wash reason | Status |
|---|---|---|---|
| Nobody picked the winning option (only possible with 3+ options, since a 2-option bet with both sides picked always has a winner) | All | "Nobody picked the winning choice" | Implemented |
| Push (an exact line; can't happen now that lines end in .5, kept as a fallback) | Total Disaster, Give And Take, Prop Hunt | "Push" | Implemented |
| Tie | Either Or | "Tie" | Implemented |
| Both players first reach the target in the same update | King Of The Hill | "Both reached it in the same update" | Implemented |
| A player in the bet didn't play | Either Or, Prop Hunt, King Of The Hill | "A player in the bet didn't play" | Implemented |
| Game canceled or postponed | Sports | "Game canceled or postponed" | Implemented |
| Game suspended more than 48 hours | Sports | "Game suspended for more than 48 hours" | Implemented |
| A tie on a 0-point spread (a moneyline) | Give And Take | "Push" | Implemented (the push rule) |
| Stats behind the score at lock, and the score moves again before they catch up | Scorcerer | "Couldn't tell the scoring play" | Implemented |
| No automatic result 48 hours after the game's scheduled start (game stuck, or player stats never delivered; suspended games keep their own rule) | Sports | "No result from the feed" | Implemented |
| Team scoring totals from player stats still disagree with the score once the grace period has passed, or two different scores (either team's touchdown or field goal, or 3 or 2) first appear in the same update | Scorcerer | "Couldn't tell the scoring play" | Implemented |
| Nobody called the result 7 days after lock (checked every ten minutes; Quick Pick originals with copies are skipped) | U2Pick | "Nobody called the result" | Implemented |
| The admin deleted a player who proposed or picked in it ([Admin account deletion](#admin-account-deletion)) | All | "A player's account was deleted" | Implemented |
| Fewer than two players at the deal (after dropping anyone who can't bet here or cover their worst case) | Jackblack | "Not enough players" | Implemented |

Every wash trigger for the LAN modes is implemented and tested (owner
decisions, 2026-11-15). Hosts can still override any automatic wash.
Scorcerer bets on which team scores next and how, away team first: in the
NFL a touchdown or a field goal (safeties and every other 2-point play don't
count), in the NBA a 3 or a 2 (free throws don't count), or No more scores.
It tells them apart from team totals of player stats (see
[Player stats](/player-stats#scorcerer-from-team-totals)).

Add any further mode-specific trigger to these tables, at lock or at
resolution, with its reason and tests.

In code: the general lock washes are in `bets/BetLockService`; mode lock rules
implement `bets/LockWashRule` (`PlayerLockRules`, `ScoreLockRules`); the
no-result wash is `SportsOutcome.orNoResult`, and uncalled U2Picks wash in
`bets/U2PickExpiry`; resolution washes are in
`bets/PayoutCalculator` (general) and the mode's resolver.

## Proposal, pick and lock

U2Pick, the first bet mode, is available end to end over HTTP and in the
frontend: proposal, pick/clear, deadline locking by the dev lock worker,
member-called resolution, host points settlement and individual/group receipt
history and actions. Balance-change warnings are delivered privately through
the pending-review list, and the private settlement realtime feed tells both
parties when to refetch it.
The score modes are covered under "Sports outcomes and host overrides".

Both mode families use the same pick, privacy, cap, lock, payout and points
settlement invariants. U2Pick validates custom questions and choices and uses
an authorized member-called result. Sports modes validate game-specific data,
baselines and applicable overtime rules; their outcomes come from the feed,
and only a host may override them (see Sports outcomes and host overrides).
Neither mode may bypass the shared transactional lifecycle.
Every mode proposes through one shared transaction (`bets/BetProposalWriter`):
authorization, the retry key, the table's betting switches (a mode gate for
U2Pick and Jackblack), the debt cap and the deadline. Modes supply only the
validated bet row; U2Pick's custom definition and settler rules stay
U2Pick's.
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
  participation order. Clearing after joining keeps a No guess participant
  while the bet is open; clearing before joining does not create one. Retry claims and changes must
  commit together. The internal transaction emits an aggregate-count
  invalidation only when the count changes; it does not emit another player's
  identity or selection, or signal a selection-only change.
- **Lock:** serialize with picks, membership and table-setting changes; recheck
  caps and apply the documented over-cap behavior. If a participant is frozen
  or lacks betting permission at lock, remove their pick. Everyone without a
  pick at lock (No guess, or a removed pick) is then out of the bet: not in
  its counts, its players or their tickets (owner decision, 2026-11-24). Commit the Pending transition and outbox event
  together. A delayed lock worker must not extend the pick deadline.
  Participant eligibility is evaluated at actual lock processing time, not
  reconstructed at the deadline.
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
- **Reads and recovery:** who's in (`participantIds`) is public in every
  status (owner decision, 2026-11-24); Active views expose only the caller's
  own pick and aggregate counts, never anyone else's pick. Admin audit views follow
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
  The internal resolver locks and rechecks eligible membership and picked
  users before calling `U2PickSettler`. A null winning
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
hosts. The same goes for `subscribedTo` (the source tables' ids), and bet reads
never send a Quick Pick copy's source bet or table id (owner decision,
2026-11-22: no other table's ids, and no raw ids on screen, Details included). Hosts can see all disabled member permissions; other members can see
only their own. Current, non-archived table membership is required even for an
administrator or invitee. The dev read endpoints use one PostgreSQL statement
to check the session, account, membership and role while retrieving the view;
they return the same not-found response for absent, archived or inaccessible
tables. They do not expose bet participation or picks.

Table names are 1–14 Unicode code points after trimming, so they fit a table
card. The API rejects longer names on creation and profile changes, and the
database enforces the same limit (`tables_name_length`).

Pending invitation reads include the table's name, emoji, up to four member
ids (in join order) and the member count, for the request card. The preview
leaves out deleted accounts and members who block the invitee, so every
listed profile is readable; the count includes everyone. An invitation still
grants no access to the table's code, chat or bets.

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
membership, with fresh READ COMMITTED checks.

**Host recovery** (owner decision, 2026-11-14). When a freeze or account deletion leaves a
table with no `ACTIVE` host, the table goes to its **oldest member**: the
earliest-joined member (`joined_at`, then user id) whose account is still
active. They're promoted like any host, with the usual "is now a host" chat
line. If no active member is left, the table is archived like a host deletion:
former members keep read-only chat for 90 days. This happens even if bets are
still open, since nobody could settle them. Unfreezing doesn't restore
hosting. `tables.TableAccountChanges` implements this, locking each affected
table after the account's user lock.

Promotion clears all member restrictions; demotion leaves those permissions
enabled and cancels that host's pending invitations for the table in the same
transaction. Existing accepted invitations remain
unchanged. A matching retry rechecks current host access and target membership
without reapplying, so a successful self-demotion retry returns 403 until the
caller is a host again. Host-change system messages store a constrained event
and subject ID, not a username; chat reads render the current username or
`Deleted user` from the tombstone. Host and chat invalidations are internal outbox rows only.

Bet creation and status writers must acquire their required user locks,
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
The schema serializes ledger writers by bet (`ledger_entries_lock_bet`) and checks the sum at commit; transactions
touching multiple bets must lock them in a consistent order and retry deadlocks.
This storage check does not implement settlement authorization, idempotency
or the required transactional outbox producer. The internal shared resolution
writer invokes the bets module's pure payout calculator and posts its results;
`POST /bets/{id}/settle` exposes it.

The calculator accepts participants in first-participation order, with no choice
for No guess. Leftover cents go to winners in the order of a deterministic
shuffle seeded by the bet ID ([Payouts](#payouts)): pseudorandom, the same on
every replay, and the same information for everyone. It returns nonzero ledger deltas in
integer cents, or a wash reason; 4fun produces no ledger deltas. A future
caller must provide validated, authoritative picks, protect the
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
The debt-cap read returns the saved value or NULL for either off state.
The retry-safe update accepts NULL or a nonnegative whole-point amount in
cents up to 2147483600. It rejects every fresh update while debt is nonzero,
including an unchanged value; a matching retry replays the prior success
without reapplying it. The cap is enforced when proposing, picking and at lock
(4fun is exempt).

The settlement module's pure debt calculator sums negative table balances per
table and negative open-receipt nets per counterparty, without offsetting
different tables or people. It checks exact cap boundaries and the 4fun
exemption. Its caller must supply current-member tables, only unsettled
non-4fun result deltas and pending wagers, and all open receipts (including
those retained after table deletion), grouped from the user's perspective.
Paid (settled) receipts must be excluded; sent receipts remain open.
Dev `GET /me/debt` gathers those inputs from a single PostgreSQL snapshot,
including corrections on unsettled results. It excludes active bets, No guess
and 4fun wagers, former or archived table memberships, and paid
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

Settling those receipts afterwards is described in [Settle-ups](#settle-ups).

Table deletion is a host action blocked while any bet is ACTIVE or PENDING or
any resolved non-4fun bet has not had its points settled. Washed and 4fun
bets do not require financial settlement. A host must be able to settle
eligible resolved bets even when their combined balance is zero for every
person: record the settlement and mark and link the bets atomically, without
creating zero-amount receipts. This avoids trapping a table when results cancel
out.

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
locking and preserve committed source-result propagation. Account deletion
("Moderation and account lifecycle") requires the user to be square first.

`GET /me/chat/latest` returns, for each current table, the newest message the
viewer would see in its chat window (the same block and deleted-author
filtering and system text), or null when there is none. Home's table cards
show it and refetch when a table's chat feed reports a change.

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
otherwise visible messages remain visible. Archive
members can read these windows for 90 days after deletion; last-read endpoints
remain restricted to active tables.

Dev reaction PUT replaces the caller's single reaction on a visible text, GIF
or bet message; DELETE removes it if present. A current, non-archived table
membership and react permission are required. The caller's outgoing blocks hide
the blocked author's text and GIF messages, but not bet cards. System and
settlement messages are not reactable. PUT accepts exactly one RGI emoji
sequence according to ICU4J 76.1 (Emoji 16.0), not arbitrary text or newer
sequences. Matching retries recheck access without reapplying; changed rows
write a table-only outbox invalidation, delivered as `chat.changed` on the
table's chat feed.

Dev chat sending requires current table membership and the corresponding text
or GIF permission. The user delegated the text limit; it is 1–2000 Unicode
code points after trimming. GIFs come only from the catalog in
`chat/gifs.json`; its IDs are the accepted ones. IDs are never reused or
removed, since sent messages look their GIF up by ID. The picker's search
needs every word to appear in a GIF's caption, tags or category. Categories are
Hype, Pain, React and Roast (Money merged into Hype, owner decision,
2026-11-23). Each player can star up to 100 library GIFs (`gif_favorites`;
`GET /me/gif-favorites`, `PUT`/`DELETE /me/gif-favorites/{gifId}`, which
set the star so repeating either changes nothing); the picker shows them first
in a Starred tab. Stars are personal data, deleted with the account.
A send atomically stores a message, internal outbox event and retry claim.
Matching retries recheck current access, permission and unchanged message content,
then return the original 201 message without later reactions. If the message
was removed or changed, replay returns 404 and does not recreate it. The retry record
stores its UUID, not a duplicate message body, and reconstructs the original
response from the matching request and immutable creation time. Future message
editing, moderation and deletion must preserve authorization and replay
semantics; a redacted or inaccessible message must not be exposed by replay.
Sending is rate limited (30 messages a minute per account); it does not yet enforce a request size or deliver events.

Reaction mutations lock the actor and session before the table, membership and
message; blocking also locks the actor, serializing the visibility check.
Future membership, archive, message, author-deletion and moderation writers
must coordinate compatible user-before-table locking and recheck state after
locking; deletion must also prevent a visible author from becoming deleted
concurrently with the reaction. Storage does not enforce this protocol.

### Polls

A poll is a chat message (`kind = 'poll'`): a question (the message text, 1–200
code points) and 2–6 options (1–80 code points each, different ignoring case),
all trimmed like text. Owner decisions (2026-11-15):

- **Who can start one.** A member with the polls permission (`can_poll`, on by
  default; hosts always have it), while the table has polls on
  (`tables.polls_enabled`, on by default). Hosts switch both: the permission per
  member (`PUT /tables/{id}/members/{userId}/permissions`, `polls`) and the
  table switch (`PATCH /tables/{id}/settings`, `pollsEnabled`, which posts a
  system message). Polls off answers 403 `polls_off`; the permission off, 403
  `forbidden`.
- **Voting.** Every current member can vote, one option each, and change or
  remove the vote at any time (`PUT`/`DELETE /messages/{id}/vote`). Polls
  never close. Turning polls off or taking the permission stops new polls
  only; existing polls keep taking votes.
- **Visibility.** Results and voter names are visible to everyone at the
  table, always. A blocked author's polls are hidden like their texts; deleted
  users' votes are hidden like reactions, and account deletion removes their
  polls and votes. Polls can be reacted to and reported.
- A changed vote writes `chat.poll.voted.v1`, delivered as `chat.changed` on
  the table's chat feed.

Chat read positions advance monotonically to visible messages. Matching
retries recheck access and visibility without reapplying the update.

The per-user idempotency guard protects table creation and membership requests,
table profile/host/permission/betting-setting changes, settings updates,
feedback, invitation responses, reactions, chat sending, polls and votes, last-read,
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
the prototype's JavaScript length check. A blank trimmed link is absent. The database
checks 5–1000 database characters to avoid rejecting valid supplementary
characters. The dev submission endpoint normalizes JavaScript whitespace,
rejects NUL and unpaired surrogates that PostgreSQL cannot safely store, and
atomically commits feedback with its 201 retry outcome. The link
constraint matches JavaScript whitespace. Feedback may contain personal
information, so account deletion removes it (`FeedbackAccountCleanup`) rather
than tombstoning the author. Sending is limited to 5 an hour per account.
Admins read it in the feedback inbox; a retention period for kept feedback is
not decided yet.

Admin flag and strike rows default to unflagged and zero strikes; an absent row
means the same. Strikes are 0–3. The moderation rules are above;
reports, strikes, feedback notes and the admin audit log are personal
moderation data, readable only by admins, and their retention period is not
decided yet.

Clearing an existing pick keeps that player in as a No guess participant
(outside the pot) while the bet is open, shown as joined to everyone; at lock
they're out of the bet (owner decision, 2026-11-24). Clearing before joining
does not create a participant. Because the backend stores No guess as no pick
row, participation is kept separately from picks. Bet reads list everyone in
(`participantIds`) in every status; the caller can always tell their own
`not_joined`, `no_guess` and `picked` states. Card games have no No guess:
leaving before the deal (anyone may, the proposer included) takes you out
completely.
Admin audit views must also hide Active participation and picks, including
user-specific participation-derived counts, until the bet locks. Admin
accounts cannot use player APIs or participate in bets. The existing schema
stores picks in plaintext; an operator with infrastructure access can inspect
or modify stored data. Pick authorization and lifecycle services do not yet
exist.
The proposer is visible as the bet's author but is not automatically a
participant.
A participant keeps their position when changing or clearing a guess and
choosing again. Future pick transactions must serialize per bet so concurrent first picks
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

The lock worker locks U2Pick and sports bets at
their deadline; Jackblack has its own worker that deals and times out
decisions (see Jackblack).

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
authorization design.
Social transactions lock both user rows in PostgreSQL UUID order before the
session and relationship rows; future follow, block, invitation, deletion and
moderation writers must use compatible ordering. Account deletion cleans up
follows, blocks and invitations (`social/SocialAccountCleanup`), and other
sessions learn of changes through the outbox relay and the personal feed (see
the [realtime contract](/reference/realtime-contract)).

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
preference mutations cancel affected requests atomically. Acceptance revalidates the policy and
atomically creates a membership, system chat line and internal outbox records;
decline only updates the invitation. Retries recheck visibility, and acceptance
replay requires current membership. The system chat line reaches members
like any table message, through the table's chat feed. Member removal cancels pending invitations involving the target;
table deletion cancels all pending invitations. Host demotion cancels its own
pending invitations. Account deletion and freezing cancel the account's
invitations using compatible locks.

Dev table creation and host member requests accept at most 100 unique IDs.
The creator is a host. For active recipients who follow the host, mutual follows
use their friends preference and one-way follows use following; `open` adds
immediately, `request` creates a pending invitation, and `closed` does neither.
Frozen, deleted, missing, blocked, already-member and already-invited recipients
all yield the same `not_added` outcome, as do the membership limits below. Each request is atomic but processes
eligibility per person. Pending invitations are private to the invite flow and
are not announced in table chat; direct additions produce a neutral notice.
Transactions lock all existing requested users and the actor in PostgreSQL UUID
order before the session and table. Matching add-member retries recheck host
access and return original outcomes without repeating actions. Creation retries
return the original outcomes and current authorized table view; lost table
access returns 404. Retry records store the submitted IDs and outcomes;
retention and account-deletion cleanup remain unresolved, as with other retry
records. The outbox relay delivers the events as realtime invalidations
(`invitations.changed` to the invitee); clients refetch, so an invitation
cancelled after its event was written is simply absent from the next read.

## Settle-ups

Implemented 2026-11-16 (owner decisions), replacing per-receipt and
all-at-once actions; Forgive removed 2026-11-18. Points have no cash value:
a settle only records that two people consider those receipts closed, and
the app never asks anyone to pay anything. The internal names (payer,
payee, `sent`, `received`, `paid`, debt) predate that wording; the app says
down, up, Settle and Mark as settled. A points settlement leaves one receipt
per person and table: who's down to whom, unpaid. A **settle** closes a chosen set of one pair's
open receipts in one action (`settlement/SettleService`, rules in
`SettleRules`).

- **Netting.** A selection can mix both directions. Seen from the person
  settling, it nets to points they're down (negative) or up (positive, or
  zero when it evens out).
- **Settle** (`sent`): only the person down. It counts as settled at once: the
  receipts close, the cards and balances move. The other person can **reject**
  it for 48 hours, which reopens every receipt in it; the rejected settle
  stays in History for audit. After 48 hours it's final.
- **Mark as settled** (`received`): the person up, or either side when the
  selection is even. Final at once, since it only gives up points owed to
  them. There is no forgive.
- **Points limit** (debt cap in code). A sent settle still counts toward
  the settler's debt until its window closes, so settling can't free room
  to keep betting. Account
  deletion also waits for open windows.
- **Limit.** At most 3 sent settles between two people in any 24 hours,
  both directions and rejected ones included (429 `settle_limit`). Marking
  as settled isn't limited.
- **Review gate.** When a new points settlement changes an existing open
  balance between two people, both must review it before settling again
  (409 `settlement_review_required`), so nobody settles against an
  out-of-date total. A pair's first receipts need no review. Settles never
  reset each other: receipts in one settle can't be in another.
- **Stale picks.** A selection must be all open receipts between the two
  (409 `stale_selection` otherwise). Matching retries return the same
  settle.
- **Storage**: `settles` (sender, kind, the net and its direction,
  `reject_by` for sends, `rejected_at`) and `settle_lines`; a receipt is
  `unpaid` or `paid` and points at the settle that closed it.
  Only a settle's rejection can change after it's written.

Cards on Friends, Settle-ups (one per person with open receipts or a send
still in its window): **yellow** when something needs you (a balance change
to review, or a send to you that you can still reject), else **red** (you
owe), **green** (you're owed) or **neutral** (even, including a pair who owe
each other equal amounts). Sorted yellow, red, green, neutral.

Realtime: every settle and rejection sends the private `settlements.changed`
invalidation to the two people. The pending-review list, each person's
review, and group reads stay private to the pair.

## Fairness

Owner rule, 2026-11-21: every mode is fair to everyone in it.

- **No information advantage.** Nobody sees anything that bears on their
  choice before anyone else: picks stay hidden until lock (who's in is
  public; which side they took isn't), and where players
  act in turns, choices are made together and revealed together (no
  sequential, visible turns).
- **No structural edge.** Proposing a bet, dealing or going first gives no
  better odds or payout.
- **Neutral ties.** A tie or an amount that doesn't divide is settled the
  same way for everyone: an exact tie washes or pushes, and leftover cents go
  to winners at random (see Payouts).
- **Exception: Hold'em's turns** (owner decision, 2026-11-22). Poker betting
  is in turn, so later players see earlier bets, and position is an edge. It's
  allowed because a game is whole cycles: everyone holds every position
  (button, blinds) equally often, so nobody keeps the edge.

## Balances

Owner decisions, 2026-11-22. There is one balance.

- **A table balance** is your results from resolved bets whose points aren't
  settled yet, minus everything you have in play: your wager in every Pending
  (locked) non-4fun pot you picked in, and each Jackblack game's worst case.
  It assumes every pending ticket loses. Active picks never count.
- **Host settle** moves only the resolved results into settle-ups; pending
  bets keep running and stay in the balance.
- **What-if** (Peers tab: tap a balance, yours or a peer's): that member's
  tickets in play, each with a "Win?" checkbox, plus All win (best case) and
  All lose (the balance). A checked pot adds its payout if the member's pick
  wins, from the resolution calculator (leftover cents included); a checked
  Jackblack game adds its best case, the mirror of the worst case. Only
  locked tickets are listed, so it shows nothing a member couldn't already
  read. Nothing is saved.
- **Your balance** (the points pill) is every current table's balance plus
  the net of your open settle-up receipts, so a host settle leaves it
  unchanged.

In code: `bets/TableBalanceController` (`GET /tables/{id}/balances`,
`GET /tables/{id}/members/{userId}/balance`), `settlement/MyBalanceController`
(`GET /me/balance`); test `MyBetsAndBalancesIntegrationTest`.

## Payouts

Owner decision, 2026-11-21: winners split the losers' pot evenly, in whole
cents; the cents that don't divide go one each to winners in a random order
(seeded by the bet, so a replay pays the same). There is no table setting for
it. Ledger entries are zero-sum, No guess is outside the pot, and a winner
whose share rounds to nothing has no ledger entry. In code:
`bets/PayoutCalculator`; test `PayoutCalculatorTest`.

## Jackblack

Owner decisions, 2026-11-21. Jackblack replaces Blackjack, where the
proposer banked every hand (an edge against the fairness rule). One ticket is
one game of several rounds:

- **Everyone deals.** The players' order is shuffled at the deal; each round
  the next player in it is the **dealer** and banks that round against
  everyone else (each seat's hand is against the dealer, not a pot). Each
  player deals once: a game is as many rounds as players (owner decision,
  2026-11-24: no rotations setting; "Run it back" plays another).
- **Who can propose** is a table setting, `jackblackAccess`: `everyone`
  (default), `hosts` or `off`. "Only hosts start bets" applies too. The
  proposer is the first player; like anyone in, they can leave before the
  deal, which takes them out completely (owner decision, 2026-11-24).
- **Ante and join window** are the shared wager slider (0.10–10 points a
  hand, or 0 for a 4fun game: hands still win, lose or push, but totals stay
  at 0) and the shared window
  tabs (default 30 s). Players join or leave until the deal, within the proposer's
  players range (the dealer counts); fewer than its minimum at the deal
  washes the game.
- **Rules**, set by the proposer, each held to a minimum and maximum (the
  server refuses anything outside them; missing ones take the default):

  | Rule | Range | Default |
  |---|---|---|
  | Doubling | on/off (any two cards, after splits too) | on |
  | Max splits | 0–3 | 1 |
  | Blackjack pays | 3:2 or 6:5 | 3:2 |
  | Decision timer | 10–60 s | 30 s |
  | Players (min–max, one slider) | 2–7 | 2–7 |

- **A round**: a fresh shoe of 1–8 decks, its size drawn at random for that
  round (owner decision, 2026-11-22; there's no Decks rule), shuffled on the
  server and never sent (see "Shuffle audit"); two cards to every seat and the dealer, the
  dealer's second face down. On an ace or ten the dealer peeks: a dealer
  natural ends the round (seat naturals push, the rest lose). A seat's
  natural is done at once.
- **Lockstep steps** (the fairness rule): everyone with a hand still to play
  chooses hit, stand, double (one card, stake doubled) or split (equal
  values, up to the max; split aces get one card each and can't be re-split;
  a split 21 isn't a natural) for their current hand. Nobody sees anyone's
  choice, only who is still deciding. Once everyone has chosen, or the
  decision timer runs out (no choice stands), the new cards are dealt in seat
  order and shown together. Reaching 21 stands automatically.
- **Dealer's turn**: when no seat has a hand left, the round's dealer taps
  one button that does what the rules require: **Flip**, then **Hit** while
  under 17, then **Stand** (soft 17 stands). A bust pays out by itself; an
  idle dealer's turn plays itself out when the timer runs out. No dealer turn
  when every seat hand is bust or a paid natural.
- **Payouts** per hand against the round's dealer: win 1:1, natural 3:2 or
  6:5 (rounded down to the cent), push 0, lose or bust −stake. Each player's
  results add up over the game; the ledger gets each player's total at the
  end (zero-sum).
- **Points limit** (owner: assume the worst): each player's worst case is
  held as pending while the game is on: every round they sit, a hand split
  to the max and doubled and lost; every round they deal, every other seat
  winning the most a seat can (that worst case, or a natural). That is
  (hand worst case + dealer's worst per seat) × (players − 1).
  It's checked when someone joins (and when proposing, for two players), and
  again at the deal for the final count: anyone who can no longer cover it is
  dropped, latest joiner first, which lowers it for the rest.
- **Between rounds** (owner decision, 2026-11-22): the finished round stays up
  for 4 seconds (phase `results`): the dealer's final hand and every player's
  result for that round, no seat's cards, then the next round deals. After
  the last round the game goes straight to its final standings.
- 4fun games play the same with nothing paid.
- Jackblack is never picked: `PUT /bets/{id}/pick` on a Jackblack bet is 400
  `invalid_choice` (joining is the only way in).

**Who sees what (owner decision, 2026-11-22).** To stop counting, nobody sees
more than the dealer and one seat at a time. During a round a player gets only
their own seat's cards and the dealer's (hole card once flipped); the round's
dealer, who makes no choices, sees every seat and swipes across them one at a
time; members not playing see only the dealer. Other seats come with just who
is there and whether they're still deciding. The server enforces this in
`GET /bets/{id}/jackblack`, so it can't be read from the network. Finished
rounds (history, Details) show every seat: each round's shoe is fresh and of a
random size, so past cards say nothing about the next.

**Shuffle audit (owner decision, 2026-11-22).** Shared by the card games
(Jackblack's shoe each round, Hold'em's deck each hand). At the deal the server
draws every round's secret: a deck count (Jackblack: 1–8 at random; Hold'em:
one) and a 32-byte seed. The ticket shows
each round's commitment from the start, the SHA-256 hex of
`cards-v1|<round>|<decks>|<seed hex>`; the deck count and seed are
revealed only once the game is over. The shoe is a fixed function of the
secret, so anyone can replay it: cards in order (per deck, suits S H D C, ranks
A 2–9 T J Q K); a word stream where block k is the SHA-256 of
`cards-v1|<seed hex>|<pass>|<k>`, eight 32-bit big-endian words per
block; a uniform number below b by drawing words until one is below
2^32 − (2^32 mod b), then taking it mod b; Fisher–Yates from the last card down
(swap card i with uniform(i + 1)). The first card is the top. Pass 0 is the
shoe; a round that runs through it continues with pass 1, 2…. Details checks
each finished round in the browser: the secret matches its commitment, and the
cards dealt are exactly the top of that shoe. In code: `bets/CardShuffle` and
`features/bets/card-audit.ts` (the same steps; tests pin the same values on
both sides).

In code: `bets/JackblackRules` (limits and worst case), `bets/JackblackGame`
(the pure game), `bets/JackblackService` (propose, join, deal, steps, the
dealer, timeouts, payouts), `bets/CardGameWorker` (deals and timeouts every
second, for both card games), on the shared plumbing in `bets/CardGames` (seats, storage, locking, the
move frame and the payout; tables `card_games` and `card_players`). Tests: `JackblackGameTest` (including 400 random games checked
zero-sum and inside the worst case), `JackblackIntegrationTest`; client
`features/bets/jackblack.ts` and its test.

## Run it back

Owner decision, 2026-11-24. A finished Jackblack or Hold'em game (resolved or
washed) offers **Run it back**: the first tap proposes the same game again,
same rules and stakes, with a 30-second join window and the tapper in it
(`POST /bets/{id}/rematch`, which needs the same proposal permission and
points limit as proposing). Later taps join that one rematch, so a game has at
most one (`bets.rematch_of`, unique). It deals like any game: with fewer than
its minimum players when the window closes, it's called off. The finished
game's reads carry `rematch` (its id, status, join deadline and whether you're
in). In code: `bets/CardGameRematch`; test `JackblackIntegrationTest`.

## Hold'em

Owner decisions, 2026-11-22. No-limit Texas hold'em, played on its ticket
like Jackblack:

- **One cycle.** A game is one hand per player at the deal (owner decision,
  2026-11-24: no rotations setting; "Run it back" plays another). Seating is
  shuffled at the deal; the button starts on the first seat and moves one seat
  (to the next player still in) each hand. Heads-up the button is the small
  blind and acts first before the flop only; otherwise the two after the
  button post the blinds and the player after the big blind acts first.
- **Stakes.** The shared wager slider is the big blind (0.10–10 points; the
  small blind is half), or 0 for a 4fun game, which plays with chips worth
  nothing (1.00 a big blind). Everyone buys in for 100 big blinds.
- **Who can propose** is a table setting, `holdemAccess`: `everyone`
  (default), `hosts` or `off`; "Only hosts start bets" applies too. Players
  join during the join window, within the proposer's players range (2–9 on one
  slider, default 2–9; fewer than its minimum at the deal washes the game);
  anyone, the proposer included, can leave before the deal.
- **Betting.** No-limit, in turn: fold, check, call, raise (at least the
  current bet plus the last full raise) or all in. An all-in for less than a
  full raise doesn't reopen raising to players who already acted (they may
  call or fold). The turn timer is the proposer's (10–60 s, default 30); when
  it runs out you check if you can, otherwise fold. While it isn't your turn
  you can arm a pre-move, Check/Fold (check if you can, otherwise fold) or
  Call any (check or call whatever the bet is); the server keeps it, only you
  see it, and it's played the moment the action reaches you, for that one
  decision. When at most one player
  can still bet, the rest of the board comes out with no more betting.
- **Pots.** Main and side pots from what everyone put in; folded chips stay in
  but folded players can't win. A pot splits evenly between the best hands;
  chips that don't divide go one each to its winners in a random order (fixed
  by the hand's secret, so it replays the same). An uncalled bet comes back.
- **Showdown.** Everyone still in shows their cards; there's no mucking,
  since the audit reveals every hand once the game is over anyway (owner
  decision, 2026-11-23). Folded hands are never shown during the game. A
  finished hand stays up for 5 seconds before the next deal.
- **Rebuy.** Stacks carry from hand to hand. A player who busts gets one
  rebuy for the whole game, offered only right after that first bust: the
  next hand waits up to 15 seconds for the answer, and letting it pass (or
  busting again) sits them out for the rest. No rebuy after the last hand.
- **Result.** Each player's final stack minus what they bought in for; the
  ledger gets everyone's total at the end (zero-sum).
- **Points limit.** Both buy-ins (200 big blinds) are held while the game is
  on: checked when joining (and proposing) and again at the deal, where anyone
  who can't cover it is dropped. The balance what-if shows a Hold'em game's
  best case as every other player's buy-ins.
- **Who sees what.** Never the deck; only your own hole cards while a hand is
  on. Stacks, bets, the board, the pot and whose turn it is are public, as at
  a real table. Members not playing see everything public and no hole cards.
- **Shuffle audit.** Each hand's deck is one 52-card deck from its own secret
  (see "Shuffle audit" above). Once the game is over every secret is revealed,
  and with it every hand, folded ones included; Details says so up front and
  checks each deck in the browser.
- Hold'em is never picked: `PUT /bets/{id}/pick` on it is 400.

In code: `bets/HoldemRules`, `bets/HoldemGame` (the pure game),
`bets/HandEvaluator`, `bets/HoldemService`, `bets/HoldemController`,
`bets/CardGameWorker`, `bets/CardGames`. Demo bots play it with `demo/HoldemBot`. Tests:
`HoldemGameTest` (including 500 random games checked zero-sum, no negative
stacks and nobody down more than two buy-ins), `HandEvaluatorTest`,
`HoldemIntegrationTest`, `HoldemBotTest`; client `features/bets/holdem.test.tsx`
and `card-audit.test.ts`.

## Membership limits

Implemented 2026-11-16 (owner decisions). Every list one person or table can
grow is capped, so no one can flood someone else's tables, requests or a
table's member list. The numbers live in `common/MembershipLimits`; each is
counted under the lock the writer already holds (the user row for a person's
counts, the table row for a table's).

| Limit | Value | When it's reached |
|---|---|---|
| Open tables per account | 100, hosting or not; deleted (archived) tables don't count | Creating a table returns 409 `table_limit` ("You're at 100 tables. Leave one first."). Accepting a request returns the same and the request stays waiting. A host's add (open or request) is `not_added`, and no request is sent. |
| Members per table | 500, hosts included | Adds are `not_added`. Accepting a request returns 409 `table_full`, and the request stays waiting. |
| Waiting requests per account | 100 | A new request is `not_added` (none is created). |
| Request lifetime | 30 days from sending | The request no longer appears, can't be answered (409, like a cancelled one), doesn't count toward the 100, and doesn't block a new request to the same table. Reads check the age directly; an hourly job (`social/InvitationExpiry`) and new requests mark old rows `EXPIRED`. |

Matching retries of a successful create or accept still replay after a limit
is reached. The caps also bound reads: `GET /tables` returns at most 100
summaries (name, emoji, your role and permissions, a four-member preview and
the member count, never whole member lists), and `GET /me/invites` at most
100 requests with the same kind of preview. `GET /tables/{id}` still returns
the full table (up to 500 member ids). The client mounts long lists 20 at a
time as you scroll (tables, requests, peers, members, followers to add).

The pace is limited too, per account (Redis rate limits, see
[Kafka & Redis](/kafka-redis)): 10 new tables an hour, 30 add-member calls an
hour (each up to 100 people), and 30 follows or unfollows a minute. Followers
have no cap: a follow is the follower's own choice, and following is already
rate limited. A table's members can be searched by username on the server
(`GET /tables/{id}/members?q=`, 50 at a time), so a 500-person table is never
loaded profile by profile to find someone.

## Sports outcomes and host overrides

Implemented (dev) for the score modes (2026-11-14), and for the player modes
and Scorcerer (2026-11-15): proposals on a stored game, locking, automatic
results and washes from the feed, and the host override. Choose Their Fate
needs GOAT play-by-play and is not implemented. Sports bets resolve from the
feed (ADR 0004, ADR 0005); [Player stats](/player-stats) has how each player
mode and Scorcerer decides.

Implementation notes:

- The server builds a score-mode bet's question and choices from the stored
  game: Total Disaster "AWY + HOM combined: over or under L?" with Over and
  Under; Give And Take "HOM ±S, who covers?" with the home side first. Lines
  end in .5 (owner decision, 2026-11-22), so they never push; the form steps
  them a whole point at a time. King Of The Hill targets are whole numbers.
- A pregame bet's picks lock at its window's end or the game's scheduled start,
  whichever is first. A delayed start never reopens picks.
- The resolution worker checks Pending score bets every 30 seconds without
  locking anything until a result is due, then settles them in their own
  transaction. Feed results have no `called_by`. The grace period after Final
  is 20 minutes. Wash reasons: "Push", "Game canceled or postponed", "Game
  suspended for more than 48 hours".
- Overrides: `POST /bets/{id}/override` (current host; sports modes only). An
  Active bet locks first. A correction appends compensating ledger entries so
  each user's sum is the new result. The bet records `overriddenBy` and
  `overriddenAt`, and chat shows `<host> set “<question>” to <choice>` or
  `<host> washed “<question>”`.

**Hosts have the final say.** A host is the person the table trusts to be fair
and to facilitate the money exchange, so a host may override any sports bet
outcome until the table's points are settled, including on bets they are part
of. After points settlement the result is final.

Automatic resolution, before any override:

| Situation | Automatic outcome |
|---|---|
| Game canceled, postponed or abandoned while the bet is Active or Pending | Wash |
| Game delayed or rescheduled | Stays Active; locks at the actual start, the first time the feed reports it live |
| Game suspended | Settles normally if it finishes within 48 hours; otherwise washes |
| Tie on a bet with no tie choice (for example an NFL moneyline) | Wash |
| Feed reports Final | Settles after a grace period of about 15–30 minutes, so quick stat corrections land first |
| Score corrected after the bet resolved | No automatic re-settlement |
| Feed outage or `unknown` status | Never settles automatically; waits, and washes if there's still no result 48 hours after the scheduled start |
| Player prop whose player did not play | Wash |
| Proposal after the game is Final, canceled or postponed | Rejected |

Sports bet rules (decided 2026-11-14):

- **Live and pregame:** a sports bet can be proposed any time until its game is
  Final, canceled or postponed, with the usual 30–600 second pick window. Picks
  lock when the window ends, or when the game starts if that comes first.
- **Whole-game totals:** player and score modes count the whole game's totals,
  whenever the bet was made. No baseline is snapshotted.
- **Overtime:** stat and score modes have an "Overtime counts" setting chosen
  by the proposer, on by default. Off means the result is decided at the end of
  regulation.
- **King Of The Hill:** the target must be above both players' current numbers
  when proposed. If either player reaches it before picks lock, the bet washes.
  If both first reach it in the same feed update, the bet washes. If neither
  reaches it by the end, Neither wins.
- **Lines:** Total Disaster, Give And Take and Prop Hunt lines must end in .5
  (the server refuses anything else), so they can't push. Either Or washes on a tie.

Host override:

- A host may **wash** a bet or **set its result** to any of its choices at any
  time while it is Active, Pending or resolved with points not yet settled. The
  server does not ask for or judge a reason.
- An override may replace an automatic outcome, including an automatic wash,
  and may correct an earlier resolution. Corrections append compensating,
  zero-sum ledger entries; they never edit or delete earlier entries.
- Overrides apply immediately, are recorded with the acting host and time, and
  post an attributed system chat message naming the bet and the new outcome.
- On a Quick Pick copy, the subscriber table's host has the same powers. Once
  they override, the copy stops following the source table's result. The
  source table's later results never change it again.
- Overrides are part of the shared transactional lifecycle: they recheck that
  the actor is a current host, lock affected users before bets, and are
  idempotent like other mutations.

Interface: the host override is the ticket's outcome menu (the bet's choices
plus a wash). In dev, scenario files and recorded chunks drive sports results
(see Dev sports data).

Open:

- A host setting for who may override (for example all hosts, or only hosts
  outside the pot) may be added later.
- Showing host-modified outcomes in the settlement message (for example a
  details popup) may be added later.
- Whether the same host override applies to U2Pick results is not decided;
  U2Pick follows its settler rule.
