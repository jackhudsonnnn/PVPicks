# Architecture diary

## 2026-11-11 — Starting the backend

### Context

`p2picks-backend-kt_v3.md` is the source of truth; its v3 addendum overrides
earlier sections. There is an existing `client/` prototype but no backend, and
the workspace is not currently a Git repository. The v3 layout calls the client
`frontend/`; keep the existing `client/` path rather than moving it during
foundation work. Adapt future build and Compose paths accordingly.

The backend is planned as a Spring Boot 3 modular monolith on Java 21. PostgreSQL
is authoritative; Redis is disposable; Kafka receives committed changes through
an outbox. The real BallDontLie feed is intended for all runtime profiles,
while a scripted feed is restricted to tests. AWS follows the LAN rehearsal.

### Work started

- Recorded the initial architecture decisions in `adr/`.
- Started the Phase 0 command mapping and domain-rule/test inventory in
  `backend-contract-inventory.md` and `domain-rules.md`. These are working
  documents, not a completed OpenAPI contract or a claim that tests exist.
- Generated `backend-test-map.md` from 253 frontend test declarations, with
  unique proposed JUnit names. Source files have since been classified;
  individual entries still need applicability review. Presentation and
  browser-only behavior should remain frontend tests.
- Began a draft OpenAPI specification at `../backend/api/openapi.yaml`; it
  covers identity, debt cap, betting, tables, Quick Picks, chat, social,
  feedback, notifications, games and settlement. Other surfaces remain
  incomplete. Its cookie name, error envelope and status codes are proposed,
  not implemented or finalized. The draft passes Redocly's minimal OpenAPI
  lint; this checks structure, not completeness or server behavior.
- Drafted `realtime-contract.md`, including authenticated subscriptions,
  privacy-preserving invalidations and refetch after reconnect.
- Added a minimal Java 21 / Spring Boot 3.5 development-only application and
  Gradle wrapper. It exposes health only, binds to loopback outside containers,
  and rejected other or mixed profiles. LAN and production remain rejected.
  JUnit tests, a live health request and packaged LAN-profile rejection passed.
- Added a Docker build and a local Compose stack with PostgreSQL 16, Redis 7
  and Kafka 3.9.2 in KRaft mode. The backend connects to PostgreSQL and Redis
  and has Kafka configured; a Testcontainers integration test checks all three
  connections. At that point migrations, seeding and frontend integration
  remained.
- Verified the isolated Compose stack's health, PostgreSQL authentication,
  Redis ping, Kafka produce/consume and backend health endpoint. Stopping
  Redis or PostgreSQL made health return 503 with bounded connection
  timeouts; health recovered to `UP` after each service restarted. Kafka has
  its own Compose health check, not an Actuator health indicator. Removed
  the isolated validation stack, its test volumes and validation images.
- Added opt-in Kafka UI (`--profile tools`) to the dev Compose stack, published
  on loopback only. Verified
  its page and API report the isolated Kafka broker online. It is unauthenticated
  and allows cross-origin requests, so it is limited to disposable local data,
  not LAN or production. Removed its isolated validation project and volume.
- Added the first shared-kernel Gradle module and a signed, integer-cent
  `Money` value. Decimal conversions reject fractions of a cent and overflow;
  addition and subtraction reject overflow. Wager limits and settlement rules
  remain domain responsibilities. Unit and integration tests passed, and the
  Docker image built with the new module. Domain modules and their boundary
  tests are still outstanding.
- Began a deeper contract review. Added persistent JSON Schema tests for
  Active-pick privacy, unresolved-result fields, invitation previews and the
  distinction between outcome resolution and later table points settlement.
  Invitees can see table names and emoji without access to the full table.
  The draft now represents No guess participants separately from pick rows after
  lock, so the client can show them without including them in the pot.
  The contract remains a draft: this does not verify a server implementation.
- Defined and tested a versioned realtime invalidation schema. Shared chat
  invalidations carry no message identity or content; notification and ticker
  invalidations carry no resource identifiers. No realtime gateway exists yet.
- Reviewed frontend test classifications by source file in
  `backend-test-map.md`. Individual proposed JUnit names still need
  reconciliation, and admin, authentication and remaining read contracts
  still require review.
- Checked all 37 frontend commands against draft operations or an explicit
  test-only exception. Eleven schema examples exercised wager boundaries,
  game requirements, overtime constraints and Active-pick privacy. These
  checks do not replace persistent contract tests or server integration tests.
- Initially left the client unchanged while starting a limited dev-only
  Phase 1 skeleton in parallel. The client was subsequently updated for
  "No guess", as recorded below. Phase 0 is still open; do not treat its exit
  criteria as passed or implement business behavior from unreviewed contracts.

### Decisions and discrepancies to track

- The addendum says usernames only; ignore the first/last-name fields in the
  earlier example profiles.
- The addendum says every table has an always-on six-character Quick Picks code.
  The current client has enable/sharing toggles and a nullable sharing code.
  The user confirmed that every table retains a code, while hosts can turn
  sharing on or off. Adapt the client without treating a disabled sharing
  setting as an absent code.
- The user confirmed that Active bet responses may include the authenticated
  player's own pick, plus counts, but not other players' picks or participant
  identities until lock. This supports refresh while refining v3's counts-only
  text. The prototype currently seats Active participants, so its real-backend
  adapter and UI must change; the standalone prototype has not been changed.
- The user confirmed that an Active bet may show its proposer as the author,
  but proposing does not automatically make that user a participant. This
  differs from the prototype, which initially inserts the proposer on No guess.
- The user confirmed that a caller may see their own participation state
  (`not_joined`, `no_guess`, or `picked`) while a bet is Active. This makes a
  prior No guess distinguishable from never joining after a refresh.
- The user renamed the player-facing PASS state to "No guess" and confirmed
  that "Pass" must be allowed as a sports-related U2Pick choice. The standalone
  prototype uses a distinct internal marker; its bet state is not persisted.
  The draft API uses `no_guess` and `noGuessUserIds`. All frontend tests and
  type-checking passed, as did backend tests; the frontend test crosswalk is
  explicitly a historical snapshot pending regeneration and review.
- The user confirmed that dev and LAN use provisioned accounts; production
  public registration is deferred until after LAN. Do not expose a public
  sign-up endpoint for dev or LAN.
- The user selected a JWT carried in an HttpOnly cookie for browser auth.
  They later chose an eight-hour, non-refreshing dev session. Dev cookie, CSRF
  and logout revocation are now implemented; LAN/prod must remain unavailable
  until their security configuration is ready.
- The user approved generating local, ignored dev signing and account
  credentials. These are now generated in ignored local files; provisioning
  has been verified in an isolated database, not run against the ordinary
  Compose database. The frontend's embedded demo passwords are not used.
- The user chose real backend dev seed data rather than a disposable mock
  backend engine. Keep the existing browser-local frontend prototype separate
  until the HTTP adapter and corresponding real APIs exist; don't imply that it
  uses the dev database.
- The v3 contract lists a receipt `dispute` operation while the current client
  changes a sent receipt back to `unpaid`. Preserve the observable unpaid
  behavior when finalizing the status model and API.
- The frontend permits washing a resolved Quick Pick before points settlement.
  An append-only ledger cannot simply erase its prior deltas; the server needs
  an atomic, zero-sum compensating entry and race protection against settlement.
- The cap is stored in integer cents in the backend; the frontend currently
  represents the setting in whole points. The adapter must convert explicitly.
- Verify that the runtime feed supplies the event chronology needed for
  first-to-milestone, next-score and possession/drive outcomes. Periodic
  snapshots alone cannot reliably establish event order or every outcome; do
  not enable automatic settlement for a mode without sufficient feed evidence.
- The user chose `America/New_York` as the games schedule's calendar timezone;
  the requested date is based on the scheduled start, including DST changes.
- OpenAPI schemas, response shapes, authentication details, subscriptions,
  permissions and JUnit equivalents are not finalized. Do not treat the
  proposed routes or names in the inventories as implemented.

### Environment and next steps

- The system JDKs detected were Java 17 and Java 8. Temurin Java 21 was
  installed under the ignored `../backend/.tools/jdk-21/` directory after
  user approval; its download checksum, `java`, `javac`, and Gradle startup
  were verified. The system Java was not changed. For local commands, set
  `JAVA_HOME` to `backend/.tools/jdk-21/Contents/Home`.
- Docker is reachable. The project Gradle wrapper and required Spring Boot
  dependencies were downloaded; the Gradle cache is kept in the ignored
  `../backend/.tools/gradle/` directory.
- Complete Phase 0: specify and review OpenAPI and STOMP contracts against all
  frontend surfaces, map existing rule tests to individual JUnit cases, and
  resolve the remaining contract questions. Then scaffold and verify Phase 1.
- The user authorized project-local Java 21 installation, dependency
  downloads and an ignored local Compose database password. Temporary tooling
  was used to inspect test declarations and validate the draft, then removed.
  Never put API keys or LAN credentials into these documents.

## 2026-11-11 — Begin database migrations

- Reviewed the four existing ADRs against v3. They remain applicable; the feed
  abstraction still needs evidence of sufficient event chronology before any
  automatic settlement of order-dependent modes.
- Added Flyway and the first, deliberately partial migration: the `users`
  table, case-insensitive username uniqueness and deletion tombstone checks.
  Live usernames are stored in normalized lowercase; a deleted identity keeps
  its ID while clearing username, hue and password hash. The migration does
  not implement account deletion, authentication or provisioning.
- PostgreSQL JDBC binds ordinary string parameters as `varchar`, so future
  username lookups must normalize input or explicitly cast the parameter to
  `citext`; the migration integration test covers the latter.
- Flyway is enabled only in the dev profile; its migration strategy also
  rejects mixed profiles before running a migration. LAN and production remain
  unavailable. A database role running migrations needs permission to install
  the PostgreSQL `citext` extension.
- Targeted integration tests passed against PostgreSQL 16, Redis and Kafka:
  migration repeatability, username lookup/constraints, tombstone constraints,
  profile guard and infrastructure connections. This is not the complete
  Phase 1 schema or a completed Phase 0 contract review.
- The full backend test task passed, and the backend Docker image built with
  Flyway. The Compose stack was not restarted for this change.
- The user selected eight-hour development sessions without refresh. Drafted
  same-origin cookie/CSRF behavior, PostgreSQL-backed immediate revocation,
  and the WebSocket handshake and delivery reauthorization requirements.

## 2026-11-11 — Dev authentication and provisioning

- Added a session migration, signed HttpOnly JWT cookies, same-origin and CSRF
  checks, provisioned-account login, logout and `/me`. PostgreSQL validates
  session and account status on authenticated requests; logout and successful
  replacement login revoke the old session. Passwords are verified with BCrypt.
- Generated the approved local signing key and random dev credentials in ignored,
  owner-only files. The provisioning script uses a database transaction, verifies
  matching existing accounts on rerun, and does not overwrite mismatched users.
  It creates users only; sample tables and bets still need migrations and seeds.
- Full backend tests passed. An isolated Compose stack built and became healthy;
  provisioning was run twice, and a generated user logged in, read `/me`, and
  could not reuse the cookie after logout. Invalid CSRF tokens and mismatched
  existing provisioned accounts were rejected. The isolated stack and its
  volumes were removed; the user's ordinary Compose data was not touched.
- A regression test caught Spring's resource-server default of bypassing CSRF
  for bearer tokens, which is unsafe when those tokens come from cookies.
  Cookie authentication now runs after the CSRF filter without that bypass.
  The test covers stale CSRF tokens on authenticated mutations.
- Redocly's minimal OpenAPI lint passes for the revised draft. This checks
  specification structure, not completeness or agreement with every endpoint.
- Dev authentication has no rate limiting. TLS, secure cookies, refresh tokens,
  login throttling, operational session cleanup and LAN deployment remain
  future work. Any future account freeze or deletion implementation must revoke
  its sessions transactionally; otherwise unfreezing could reactivate an old
  unexpired session. Realtime authentication is still a contract, not a gateway.
  The rest of the Phase 0 and Phase 1 checklists remain open.

## 2026-11-11 — Domain module boundaries

- Added the eleven v3 domain Gradle projects with empty `api` and `internal`
  package skeletons. Spring Modulith 1.4.14 is aligned with the existing Spring
  Boot 3.5.16 version. The app scans the new module root, and the shared kernel
  is available to each domain project.
- Spring Modulith verifies module discovery, named APIs and dependency cycles.
  An additional ArchUnit rule restricts cross-module and app access to `api`
  packages, including disallowing access to a module's root package. Domain
  modules cannot depend on app wiring; the shared kernel cannot depend on either.
  Test fixtures demonstrate allowed API use and rejected internal access and
  cycles, so the checks are not merely passing on empty projects.
- The full backend test task passed, including a test-only component proving
  Spring scans the new module root. The boot jar contains all eleven modules,
  and the backend Docker image built. Current dev authentication still resides
  in `app`; the identity skeleton does not yet contain that implementation.
- These boundaries are only one part of Phase 1. The remaining database
  schema, sample tables/bets and Phase 0 contract and test-map reviews are
  still outstanding.
- Reconciled the current 35 payout, picks and U2Pick frontend cases individually
  in the test crosswalk. Targets remain unimplemented; the prototype's
  missing-host fallback is explicitly unresolved, and option deduplication
  is distinguished from server-side validation. The rest of the crosswalk
  remains a historical snapshot requiring case-by-case review.
- The user accepted the recommendation that the home ticker remain sports-only,
  matching the current prototype. Drafted `GET /ticker` and its generic
  invalidation, including overnight live games; it does not expose bet activity.
  This refines v3's earlier bet-lifecycle ticker consumer. Feed freshness,
  partial-data policy and the gateway remain open; the endpoint is not implemented.
- Targeted ticker and realtime schema tests passed, including rejection of
  private fields; the revised OpenAPI draft passes minimal lint. Date-boundary,
  ordering, outage and gateway behavior remain future server tests.

## 2026-11-11 — Social relationship schema

- Added the next Flyway migration for directed follows and blocks. Foreign keys,
  composite uniqueness, self-relation checks and inverse-lookup indexes are in
  place. These tables do not themselves implement block suppression, follow
  removal or account-deletion cleanup; those must be transactional domain logic.
- PostgreSQL integration tests passed for both relationship directions,
  duplicate/self/missing-user rejection, and migration from a populated
  version-two database without losing its users. Migration reruns are no-ops.
- The full backend suite passed on retry. One preceding run failed before
  application startup because the Kafka Testcontainers startup script reported
  `Text file busy`; the following run started the container successfully.

## 2026-11-11 — Table schema and random Host remainder

- The user chose a random host for the Host remainder rule when a table has
  multiple hosts. The draft contract selects uniformly among current hosts at
  lock and persists that selection with the rule; retries and subsequent host
  changes must not change a committed selection. Deletion retains the selected
  user's tombstone. A missing host fails the lock rather than silently
  redirecting the remainder. The Host recipient is not necessarily the host
  who initiates table points settlement. This refines the prototype's
  first-host selection; the selection and settlement logic do not yet exist.
- Added the draft `remainderHostId` bet response field and schema tests for its
  lock-time presence and rule-specific applicability. Future integration tests
  must cover random selection, retries, deletion, races and zero-sum settlement.
- Added Flyway V4 for tables, multi-host memberships and pending invitations.
  A code is retained whether or not sharing is enabled; constraints cover its
  shape and uniqueness, membership references and host permissions, and
  duplicate pending invitations. The schema alone does not ensure every table
  has a host, authorize invitations or perform host handoff. Domain operations
  must enforce these invariants transactionally and serialize against bet lock.
  Quick Pick subscription storage, domain APIs, seed tables and bets remain
  unimplemented.
- PostgreSQL integration tests exercise the new constraints and migration of
  populated V2 and V3 databases. Contract and integration tests passed; the
  Phase 0 review and Phase 1 skeleton are still incomplete.

## 2026-11-11 — Quick Pick subscriptions and outbox storage

- Reviewed all 15 current Quick Picks helper test cases individually in the
  crosswalk. The contract now specifies subscription authorization and makes
  explicit that disabling the feature disconnects both incoming and outgoing
  subscriptions while retaining the table's code and existing copies.
  Rotation or disabling sharing disconnects subscribers; copies never chain.
  Backfill, fan-out and changes to subscriptions must be coordinated under
  concurrency. Their services and behavior tests do not yet exist.
- Added V5 for directed Quick Pick subscriptions, rejecting duplicates,
  self-subscriptions and missing tables while allowing reciprocal subscriptions.
  Added V6 for outbox event storage and an unpublished-event index. No event
  producers, Kafka relay or deduplicating consumers have been implemented;
  relay ordering, concurrency and retention remain open design work.
- PostgreSQL integration tests passed for the subscription constraints,
  commit and rollback of a domain row and outbox row together, and successive
  upgrades from populated V2 through V5 databases. Minimal OpenAPI lint passed
  after the contract updates. The ordinary Compose database was not changed.
  The remaining schema, seed tables and bets, contract reviews and domain
  services are still outstanding.

## 2026-11-11 — Bet and participation storage

- Added V7 for bet proposals, lock-time remainder snapshots, Quick Pick source
  references, participants and picks. A separate participant row retains a
  player's place after clearing a guess; no pick row represents No guess. The
  first participation order also preserves the prototype's earliest-pick
  behavior across edits and clearing/re-picking. Future concurrent pick
  transactions must serialize per bet, rather than treating sequence
  allocation as commit order.
- Database constraints check wager bounds, required host snapshots, consistent
  copy/source table references and one copy per source and target. They do not
  implement the bet state machine, snapshot immutability, host selection,
  permissions, valid choices, debt caps, privacy, copy non-chaining or payouts.
  Those remain domain-service responsibilities. The version column does not
  provide concurrency control until services use it.
- PostgreSQL integration tests passed for No guess storage, sports "Pass",
  wager boundaries, lock snapshot shape, copy uniqueness/source integrity and
  upgrade from a populated V6 database. The ordinary Compose database was not
  changed.
- Table deletion and historical retention still need reconciliation before
  implementing deletion: these foreign keys deliberately do not cascade, and
  source bets may be referenced by copies at other tables. The current prototype
  drops a deleted table's local bets, whereas persistent ledger/history and
  surviving Quick Pick copies require an explicit backend retention design.
  Seed tables/bets, the ledger and the remaining Phase 0 and Phase 1 work are
  not yet implemented.

## 2026-11-11 — Ledger storage safeguards

- Added V8 append-only ledger storage in integer cents. Inserts lock the
  associated bet, and a deferred PostgreSQL constraint checks that all of its
  entries sum to zero at commit. A composite foreign key prevents entries for
  4fun bets or the wrong table and prevents changing a bet to 4fun after
  entries exist. Updates, deletes and truncation of entries are rejected;
  corrections must append zero-sum compensating entries.
- PostgreSQL tests passed for balanced payout-shaped entries and compensation,
  unbalanced commit and outbox rollback, 4fun/wrong-table rejection,
  append-only enforcement, serialized concurrent writers, and concurrent
  unbalanced transactions. Upgrade from a populated V7 database also passed.
  The ordinary Compose database was not changed.
- These are database safeguards, not a payout engine, settlement service,
  idempotency mechanism or outbox relay. Future multi-bet transactions need
  consistent lock ordering and deadlock retries; production roles must not
  have privileges to disable triggers. The commit-time check runs per inserted
  entry, so its cost and the v3 end-to-end 50-person settlement target still
  need measurement once settlement exists. Phase 0 and Phase 1 remain open.

## 2026-11-11 — Settlement and debt-cap storage

- Added V9 storage for nullable, nonnegative debt caps; separate settlement
  records; immutable receipt identities and amounts; mutable receipt statuses
  and revisions; forgiveness timestamps; and links from settlements to bets.
  Each receipt includes its settlement host as payer or payee, and repeated
  table settlements retain distinct receipts. A bet can be linked to only one
  table settlement.
- PostgreSQL integration tests passed for receipt identity and association
  constraints, status storage, off/zero/positive cap values and upgrading a
  populated V8 database with ledger entries. Reviewed all five current
  settle-receipts cases individually in the test crosswalk; the actual receipt
  service tests are still future work. The ordinary Compose database was not
  changed.
- Storage alone does not enforce receipt transition authorization, revision
  updates, forgiveness cooldowns, group-net reconciliation or cap calculations.
  A future settlement transaction must link eligible bets and update their
  points-settled timestamps together; these two representations are not
  cross-checked by the schema. Table deletion versus retained financial history
  remains unresolved. The remaining Phase 0 review, schema, dev seeds and
  domain APIs are still outstanding.

## 2026-11-11 — Chat storage and unread contract

- Added V10 for typed chat messages, one reaction per user/message and
  per-user read positions. A table row lock precedes message-order allocation,
  so concurrent committed messages cannot be inserted behind an already
  observed message in the same table. Creation timestamps are informational;
  pagination must use the stable order. Read positions retain their order even
  when the corresponding message is deleted, and deleting a message removes
  its reactions. The schema deliberately leaves visibility, membership and
  monotonic read-position updates to future services.
- Drafted a filtered unread-state GET alongside last-read PUT, and explicit
  response shapes for each chat message kind. Settlement chat includes static
  parties and amounts, not live receipt status; bet chat cannot contain picks.
  Reviewed all five reaction and five unread frontend cases in the crosswalk,
  distinguishing idempotent API operations from the prototype's toggle.
- PostgreSQL integration tests passed for message shapes, table references,
  reactions, deleted-message read markers, concurrent ordering and a populated
  V9 upgrade. Chat schema tests and minimal OpenAPI lint passed. No chat API,
  filtering or realtime gateway is implemented; the ordinary Compose database
  was not changed.
- The table lock also serializes other writers that take that lock. Future
  services must use a consistent table/bet lock order, handle deadlocks, and
  measure the end-to-end chat latency target. Remaining Phase 0 review, schema,
  dev seeds and domain services are still outstanding.

## 2026-11-11 — Notification storage and chat-read decision

- The user chose to mark chat notifications read when that user's chat read
  position advances, rather than relying on browser presence. This applies
  across sessions; notifications delivered after their message is already
  read must start read. The draft API records this behavior and keeps inbox
  mark-all-read separate from chat read positions.
- Added V11 notification preferences, muted tables and inbox storage. Per-user
  sequence assignment is serialized, retained notifications have unique
  recipient/event/key identities, and chat notifications reference their
  actual message, table and author. Deleting a source chat message removes its
  notifications. The prototype's 200-item cap and 15-item page are documented
  draft server targets, not implemented behavior.
- PostgreSQL integration tests passed for preference defaults, source integrity,
  uniqueness, message deletion, concurrent inbox ordering and upgrade from a
  populated V10 database. Minimal OpenAPI lint passed. Reviewed all three
  notification-history cases individually in the test crosswalk. The ordinary
  Compose database was not changed.
- Notification production, read-position synchronization, retention, authorization,
  block filtering and account-deletion cleanup are not implemented. Producers
  must not persist personal text without traceable subject references, and
  deletion must remove or anonymize affected inbox content. The unique index
  only deduplicates retained rows; event-consumer deduplication must survive
  inbox clear and eviction. Future read-position and notification writers must
  coordinate locks and test delayed deliveries and cross-session races.

## 2026-11-11 — Feedback storage and moderation retention

- Added V12 feedback storage with a submitter reference, five categories,
  basic text and link checks, and indexes for submitter cleanup and moderation
  reads. The submission service must trim input, enforce the complete
  whitespace and length contract, authorize access and handle personal data
  on account deletion; the database checks are not a substitute. Its 5–1000
  database-character bound deliberately permits five supplementary characters,
  which meet the prototype's ten UTF-16-code-unit minimum.
- Reviewed the three feedback test cases in the crosswalk. The draft request
  contract now permits the prototype's optional blank link, surrounding
  whitespace and case-insensitive HTTP(S) prefix. Text length is specified
  after trimming in prose because raw JSON Schema length constraints cannot
  express that normalization. User-supplied links must not be fetched.
- The user asked for a compliance-conscious approach to reported-message
  snapshots and auditability after deletion, rather than selecting a retention
  policy. Applicable jurisdictions, retention periods, legal holds and what
  personal evidence may be kept remain unconfirmed. Do not treat any retention
  choice as legally compliant without review; report and audit storage and
  account-deletion behavior remain open and must be resolved before real-user
  deployment. Restrict moderation access and minimize sensitive audit payloads.
- PostgreSQL storage tests, the populated V11-to-V12 upgrade, feedback request
  contract tests and minimal OpenAPI lint passed. Tests used disposable
  containers; the ordinary Compose database was not changed.

## 2026-11-11 — Minimal development seed

- Added an explicit, one-time development seed after account provisioning:
  two tables, memberships, two active U2Pick bets, one No guess participant
  and a literal sports choice of Pass. Newbie starts without a table; the
  intermediate and pro accounts share the samples. This is not the prototype's
  full historical/load-test dataset and does not fabricate game-feed results,
  financial history or events.
- The v3 plan proposes a dev-gated repeatable Flyway migration. Instead, the
  seed is an explicit Compose-dev command: Flyway runs before the separate
  account-provisioning step, and an automatic repeatable seed could restore or
  overwrite sample data after a developer edits it. A transaction-scoped lock
  and database marker serialize concurrent attempts and prevent reruns from
  resetting data. Missing accounts or code conflicts roll back the seed.
- PostgreSQL integration tests cover initial data, preserved edits/deletions,
  concurrent runs and rollback on missing accounts or conflicts. The script
  was also run twice against a separately named, disposable Compose database;
  that project's resources were removed after verification. The ordinary
  Compose database was not changed. Domain APIs and the HTTP frontend adapter
  remain unimplemented.

## 2026-11-11 — Admin visibility and sealed-pick exploration

- The user confirmed that admins must not see another user's Active
  participation, choices or user-specific participation-derived counts.
  Picks may be revealed when the bet locks and becomes Pending. Admins who
  are table members retain the same own-pick and table-wide aggregate access
  as other members. The current schema stores picks in plaintext; the rule
  is not implemented and would not by itself hide data from a database
  owner or operator.
- The user asked about keeping picks private even from P2Picks before lock.
  Scope is not yet chosen: concealing choices alone is more practical than
  concealing participation, since authorization, counts and debt reservations
  require server-side metadata. Local-only picks can disappear and cannot be
  reliably committed before the deadline. Client-side commitments with later
  reveal can avoid pre-lock plaintext but need an explicit policy for missing
  or invalid reveals; an independent release authority may improve automatic
  recovery but adds trust and operational complexity. Neither is implemented
  or selected.
- PostgreSQL row-level security is defense in depth against query mistakes
  under restricted roles, not protection against superusers, BYPASSRLS roles,
  database owners able to change policies, or compromised application code
  that can impersonate users. A realistic threat model must also cover
  tampered browser code, metadata and timing, replay, key release, recovery,
  deadline manipulation, and independent verification of accepted picks.
  Choose an architecture only after resolving those tradeoffs and reviewing
  it with a security specialist; do not claim cryptographic guarantees based
  solely on application authorization or encryption at rest.
- The user specified reveal at the transition to Pending and chose to defer
  the stronger privacy architecture decision. Do not silently adopt sealed
  picks, a key custodian, or a missing-reveal policy. Admin visibility is
  recorded as a draft contract; RLS and admin endpoints are not implemented.
  Minimal OpenAPI lint passed after the contract clarification.

## 2026-11-11 — Choosing a privacy direction

- The user asked for the fairest, most private and convenient approach and
  considered cancelling a pick if the player's device dies before lock.
  Prefer an automatically revealed client-sealed submission over local-only
  storage or voluntary reveal: once accepted, device loss should not erase a
  pick or permit selective non-reveal to avoid a loss. The server may still
  need participation and reservation metadata for limits and counts.
- This is a proposed design target, not an implemented feature or proven
  operator-blind guarantee. An independently controlled release authority
  must not release keys before the independently verifiable lock condition;
  its operators and client code remain trust assumptions. A server delivering
  modified browser code could steal a choice before encryption. Published,
  independently verifiable client builds and additional controls would need
  evaluation before stronger claims.
- Design the release and acceptance protocol, legitimate-choice validation,
  key handling, early or changed lock conditions, user device recovery and
  multi-device access, replay protection, tamper evidence and failure behavior
  before implementation. If release is unavailable, do not settle using an
  empty or partial set or silently convert a valid pick to No guess. Ensure
  chosen lock/reveal availability semantics do not claim instant reveal during
  an outage. Independently review the protocol and test adversarial scenarios.
- The current API sends plaintext choices and V7 stores them in plaintext;
  changing only RLS, encryption at rest or browser local storage cannot meet
  the stronger target. No sealed-pick or key-release code has been added.

## 2026-11-11 — Admin actions and flag storage

- Drafted the admin user-action request for freeze, flag, strike, clear and
  note, following the prototype's strike cap and manual unfreeze behavior.
  The contract calls for an active admin, CSRF, atomic actor/action recording,
  per-target serialization and idempotent retries. Freezing revokes sessions;
  clearing strikes does not unfreeze. The server rejects blank notes rather
  than silently ignoring them. Rules for actions on admins and deleted users
  are still open.
- V13 stores only a flag and a bounded strike count per user. These are personal
  moderation data, not public profile fields. The schema does not perform
  authorization, freezes, session revocation, action auditing or deletion
  cleanup; all require future services. Report and audit history retention
  remains unresolved.
- Reviewed all six admin-page cases individually, separating simulated health
  from real telemetry and deriving net from ledger entries and corrections.
  Admin-action schema, flag storage, populated V12-to-V13 upgrade and dev-seed
  tests passed, as did minimal OpenAPI lint. There is no admin endpoint yet;
  the ordinary Compose database was not changed.

## 2026-11-11 — Dev admin route boundary

- The dev security chain now protects both `/admin` and `/admin/**`. It
  rechecks the session, active status and current admin role in PostgreSQL for
  each request rather than trusting a role embedded in a JWT. Players receive
  403 and invalid, frozen or revoked sessions receive 401. Mutations still
  require CSRF and same-origin checks. No admin endpoint or RLS is implemented.
- Tests use a test-only controller to verify that an admin request reaches a
  handler while player, unauthenticated, demoted, frozen and revoked sessions
  cannot. They also cover promotion/demotion with the same session, root and
  nested paths, admin POST CSRF and existing login/logout behavior. Targeted
  PostgreSQL, Redis and Kafka-backed tests passed.
- This is a request-level boundary, not a transaction-level authorization
  guarantee: a role or session can change while a request is in flight.
  Future sensitive reads and mutations must revalidate and coordinate with
  revocation in their own transaction. The database role used by the dev
  application can read plaintext picks; this does not meet the proposed
  operator-blind privacy target.

## 2026-11-11 — Dev admin health snapshot

- Implemented the admin-only `GET /admin/health` using the existing PostgreSQL
  and Redis Actuator indicators. The non-cacheable response contains statuses
  and a timestamp, not connection details. Kafka is explicitly `NOT_CHECKED`;
  there is no overall-health claim, latency/error telemetry or invented
  threshold. Missing or unsupported indicators fail explicitly. Authentication
  depends on PostgreSQL, so a database outage may prevent this endpoint from
  responding.
- The OpenAPI schema and targeted tests cover role and session access, response
  shape, unavailable/unknown indicators and public health privacy. A separate
  isolated Testcontainers run stopped its own Redis container and verified
  the admin response reported `DOWN` while public health returned 503.
  Minimal OpenAPI lint passed. The ordinary Compose stack was not changed.

## 2026-11-11 — Durable event-consumer markers

- V14 adds PostgreSQL processed-event markers, unique by logical consumer and
  event ID. They are independent of outbox and inbox rows, so clearing an inbox
  or pruning an outbox row does not by itself remove the marker. The event ID
  must be stable and unique across producers for each consumer. These markers
  may be linkable to personal data and require retention/access review.
- Tests exercised consumer-specific uniqueness, an inbox clear followed by
  replay, rollback of the marker when the effect fails, concurrent duplicate
  processing and populated V13-to-V14 migration. No real consumer, publisher,
  relay, pruning or dead-letter handling exists. The test transaction is an
  example of the required atomicity, not an implemented notification service.
- Future consumers must commit the marker with all effects before acknowledging
  the broker. One event with many recipients requires atomic fan-out or a
  designed per-recipient work identity; a single marker cannot safely cover
  partial work. External side effects require their own durable delivery
  mechanism. Do not prune markers before defining compatible replay, backup and
  dead-letter horizons. HTTP idempotency remains a separate unresolved design.

## 2026-11-11 — HTTP retry window

- The user selected a 24-hour duplicate-prevention window, matching v3's
  response-cache baseline. The draft scopes keys to the authenticated user
  across operations, detects changed operations, targets and payloads, and
  requires concurrent matching requests not to apply effects twice. Retain
  the authoritative record in PostgreSQL for at least 24 hours after commit;
  Redis may cache but is not the source of correctness.
- A retry must pass current authentication, authorization and visibility before
  receiving a recorded outcome. It must not re-run the original mutation
  merely because its business preconditions have since changed. Account
  deletion can end authentication, so replay is not a promise of an identical
  response. After the window, clients must reconcile rather than blindly retry.
- The exact request fingerprint, canonicalization, in-flight responses,
  failure handling, safe response retention and account-deletion cleanup are
  still unimplemented and require review. Do not retain raw bodies or plain
  low-entropy pick hashes that expose choices. HTTP keys and broker
  processed-event IDs are separate mechanisms.

## 2026-11-11 — Table deletion and retained history

- The user chose to require table points settlement before deletion, keep open
  receipts usable in personal settlement history, and retain chat in a
  read-only archive. This adds a settlement prerequisite to v3's original
  no-Active/Pending rule and differs from the prototype, which removes the
  table, messages and local bets even when balances remain.
- The user delegated the remaining choices: limit archive access to members at
  deletion, subject to blocks and account deletion, rather than granting
  access to former members; and let hosts settle zero-net resolved non-4fun
  bets without creating payment receipts. The latter is necessary because the
  prototype disables settlement when all member nets are zero. Washed and 4fun
  bets do not require points settlement.
- Deletion must preserve receipts, financial history, surviving Quick Pick
  copies and committed propagation to them. It must disconnect subscriptions
  and coordinate membership, bet, settlement, chat and subscription writers
  with deletion. Open receipts continue to affect person nets and debt.
- V15 adds an archive timestamp and separate archive-member snapshot storage.
  Existing tables remain unarchived; snapshot identities have table/user
  foreign keys and are independent of later membership removal. This is
  storage only: the database does not ensure the snapshot is correct, the
  eligibility checks are met, or an archive is read-only.
- Targeted PostgreSQL tests passed for snapshot references and uniqueness,
  membership independence, rollback and a populated V14-to-V15 upgrade.
  The updated draft OpenAPI passed minimal lint; no deletion service was tested.
- These are draft requirements, not implemented behavior. Archive discovery,
  authorization details, event delivery, account-deletion interactions,
  retention periods and legal holds still require design and appropriate
  privacy/legal review. The required domain services and APIs do not exist.

## 2026-11-11 — Pure bet payout calculator

- Added an internal, pure payout calculator using integer-cent `Money` values.
  It recognizes No guess separately from a sports `pass`, reports the four
  prototype wash conditions, honors the lock-time remainder recipient and
  first-participation order, and emits no zero-value ledger entries. A losing
  proposer can receive the remainder; a missing host or proposer recipient is
  rejected rather than silently redirected. 4fun keeps the result or wash
  decision but produces no financial deltas.
- Random remainder uses the prototype's 32-bit shuffle seeded by the bet ID,
  as v3 specifies. It is deterministic pseudorandomness, not a secret or
  independently verified draw; the future server must control bet IDs and
  prevent manipulation of payout inputs.
- Nine targeted calculator tests passed, including 500 generated pots across
  all four remainder rules and a prototype shuffle comparison; five module
  architecture tests also passed. This is not a full backend-suite run.
- This is not a bet resolution or settlement service. Picks, authorization,
  snapshot retrieval, atomic ledger/outbox writes, retries, host selection,
  sealed-pick release and Quick Pick propagation remain unimplemented.
  The 50-person end-to-end settlement target has not been measured.

## 2026-11-11 — Pure table settlement calculation

- Added an internal calculator that totals complete per-bet ledger deltas and
  produces positive-amount payer/payee receipts through the chosen host. It
  checks each bet's zero-sum invariant and rejects duplicate or absent input.
  Intermediate totals use arbitrary precision so cancellations are independent
  of input order; an unrepresentable final host net or receipt fails explicitly.
- Zero-net results retain their bet IDs in the plan with no receipt lines,
  supporting the deletion prerequisite without inventing zero-value payments.
  The caller must provide only eligible bets from the right table and perform
  authorization, synchronization and all persistence. The calculator is not
  connected to an endpoint or a database transaction.
- Targeted settlement, payout and architecture tests passed, including 200
  generated settlement plans. The full backend suite was not run.

## 2026-11-11 — Dev table reads and visibility

- The user chose host-only Quick Pick codes and disabled-permission visibility
  for hosts or the affected member. Updated the draft Table contract accordingly.
- Added dev `GET /tables` and `GET /tables/{id}`. They require a current
  membership, hide archived tables and give the same 404 for inaccessible and
  absent tables, including for invitees and non-member administrators. They
  never return bet participation or picks. Their single database statement
  checks the session and active account together with the current membership,
  host role and table state; responses use `Cache-Control: no-store`.
- Table writes, archive discovery, frontend integration and the other read APIs
  remain unimplemented. This dev controller remains in `app`, like dev auth;
  it is not a production authorization or RLS solution.
- Targeted integration tests passed for host/member redaction, invitee and
  admin denial, archive hiding, promotion and demotion, membership removal,
  revocation and schema validation. Authentication and module-boundary
  regression tests and minimal OpenAPI lint also passed; no full suite was run.

## 2026-11-11 — Dev settings reads

- Implemented `GET /me/debt-cap` and `GET /me/notification-preferences`.
  An absent debt-cap row and a stored NULL both mean disabled; an absent
  notification preference row uses the V11 defaults. The user's saved mute
  IDs are returned separately, including any stale or archived IDs until
  membership and deletion cleanup is designed.
- Each query checks the current session and active account in the same
  statement as the data read. Responses are private and marked `no-store`.
  These endpoints neither change settings nor calculate debt, enforce a cap,
  authorize notification delivery or implement account-deletion cleanup.
- Targeted integration tests passed for defaults, stored settings, isolation,
  numeric bounds, frozen accounts and revoked sessions, along with notification
  storage tests. Minimal OpenAPI lint passed; the full suite was not run.

## 2026-11-11 — Dev relationship read

- Implemented `GET /me/relationships` with the caller's following, followers
  and outgoing blocks. It suppresses follows across blocks in either direction
  and excludes deleted identities, but preserves existing relationships with
  frozen users. Incoming blocks are not returned. The query checks the active
  account and session in the same statement; responses are `no-store`.
- Filtering protects the read but does not repair inconsistent database rows.
  A future block transaction must remove follows and pending invitations in
  both directions, and deletion must clean up personal relationships. Unblock
  must not restore old follows. Follow/block mutations, notifications and
  frontend integration remain unimplemented.
- Targeted integration tests passed for mutual follows, private incoming
  blocks, stale blocked follows, frozen and deleted identities and session
  revocation. Settings and module-boundary regression tests and minimal
  OpenAPI lint also passed; the full suite was not run.

## 2026-11-11 — Dev invitation read

- Implemented `GET /me/invites` for the caller's pending invitations, including
  only table name and emoji for display. Archived tables, deleted senders,
  existing memberships and blocks in either direction are filtered. A frozen
  sender's existing invitation remains visible without exposing their status.
  An invitation does not grant access to the full table.
- Session and account validity are checked in the same statement as the read.
  Creating, accepting, declining and cleaning up invitations remain
  unimplemented. Tests passed for filtering, table-access denial, per-user
  isolation, ordering, schema and revocation, with table/relationship and
  module-boundary regressions; minimal OpenAPI lint passed.

## 2026-11-11 — Retry-safe notification preference updates

- Added dev `PUT /me/notification-preferences` and V16 HTTP retry storage.
  The caller replaces the full preference set. Newly muted tables require
  current membership in a non-archived table; the user chose to allow existing
  stale mutes to be retained or removed. Removed stale mutes cannot be re-added
  without current membership.
- A required key is scoped to the authenticated user across operations.
  Canonicalized settings and sorted mute IDs are fingerprinted with a separate
  HMAC key; raw retry keys and request bodies are not stored. The active user
  and session are checked before replay, and the claim and update commit
  together. Matching retries return 204 without repeating the update; changed
  requests conflict. Invalid new mutes and expired sessions roll back claims.
- A database key verifier fails closed if the fingerprint key changes. The
  user approved generating an independent ignored, owner-only dev key; local
  initialization was run twice, without provisioning or modifying the ordinary
  Compose database. Key rotation and recovery are not implemented: restoring
  the original key is required if it is lost or replaced.
- Records are not pruned, so they persist beyond the minimum 24-hour
  post-commit guarantee. `created_at` is not a commit timestamp and must not
  be used naively for pruning. Account-deletion cleanup, legal/privacy
  retention, safe pruning and responses other than 204 remain unresolved.
  Clients should reconcile after the guarantee rather than retry blindly.
- Only this dev mutation uses HTTP retry protection. It does not emit outbox
  events or realtime invalidations, and settings are not yet used to deliver
  notifications. HMAC fingerprints are not operator-blind pick protection.
- Focused PostgreSQL tests passed for concurrent retries, changed operations
  and payloads, stale mutes, rollback, expired and revoked sessions, and a
  changed fingerprint key. The full `:app:test` backend suite also passed,
  including the populated migration upgrade and architecture checks. Compose
  configuration, provisioning-script syntax and minimal OpenAPI lint passed.
  An isolated Compose build started all services and returned healthy; its
  verification containers, volumes, network and image were removed. The
  existing Compose stack was not rebuilt or migrated.

## 2026-11-11 — Retry-safe inbox mark-read

- Implemented dev `POST /notifications/read-all` for the caller's existing
  inbox entries. It preserves already-read timestamps and does not advance chat
  read positions. The endpoint accepts no body and emits no realtime event.
- The update uses the same per-user idempotency guard as notification
  preferences. A matching retry returns 204 without marking later arrivals;
  reusing a key across operations conflicts. Insertion and mark-read acquire
  the recipient user lock first, serializing the boundary between current
  notifications and later arrivals. Future notification writers must follow a
  compatible lock order.
- Extracted the shared active-user/session lock and expiry check used by both
  mutations. Both recheck expiry after their writes so a session expiring
  while waiting for a row lock rolls back the mutation and its retry claim.
  Retry records still have no pruning, deletion cleanup or key rotation.
- Inbox reads, clearing, delivery, cap enforcement, realtime invalidation and
  chat-read synchronization remain unimplemented; this is not a complete
  notifications feature.
- Targeted integration, notification-storage and module-boundary tests passed,
  including cross-operation conflicts, later arrivals, serialized insertion,
  and expiry during a blocked write. Minimal OpenAPI lint passed. The full
  backend suite was not rerun for this change.

## 2026-11-11 — Invitation preferences

- Added V17 invitation-policy storage and dev `GET`/`PUT
  /me/invite-preferences`. An absent row defaults to friends `open` and
  following `request`, matching the prototype. Both modes accept `open`,
  `request` or `closed`; following means hosts the invitee follows who do
  not follow back. Updating the policy leaves existing invitations unchanged.
- The PUT uses the shared per-user retry guard and rechecks session validity
  before replay and after writes. A matching retry cannot revert a later
  update; a changed payload or operation using the same key conflicts.
  Preference changes do not emit realtime events.
- Invitation creation, response, relationship/block checks and policy
  enforcement are not implemented. Future invitation transactions must
  coordinate with changes to these preferences and relationships. Account
  deletion cleanup and HTTP retry-record retention are also unresolved.
- Targeted integration and module-boundary tests passed for defaults,
  isolation, validation, retries, concurrent conflicting payloads, expired
  sessions and database-failure rollback. A populated V16-to-V17 migration
  test and minimal OpenAPI lint passed. The ordinary Compose database was
  not migrated, and the full backend suite was not rerun for this change.

## 2026-11-11 — Retry-safe feedback submission

- Implemented dev `POST /feedback` with a bodyless 201 response. It validates
  the prototype's five categories, 10–1000 UTF-16-unit text limit and optional
  HTTP(S) link after JavaScript-compatible whitespace trimming. NUL and
  unpaired surrogates are rejected because PostgreSQL cannot safely store
  them. No moderation reads or rate limiting are implemented.
- V18 permits bodyless 201 as well as 204 retry outcomes. Feedback and its
  keyed fingerprint commit in one transaction; matching retries return 201
  without another submission, changed requests conflict, and unexpected
  failures roll back the claim. The retry record contains neither feedback
  text nor a response body. Other response shapes still need design.
- Testing exposed a mismatch between PostgreSQL's locale-dependent whitespace
  class and JavaScript's. V18 replaces the link check for new writes with an
  explicit JavaScript whitespace set. The new constraint is `NOT VALID` so
  existing feedback is neither changed nor rejected during upgrade; legacy
  rows still need review before validation.
- Targeted integration, feedback-storage, populated V17-to-V18 upgrade and
  module-boundary tests passed, including concurrency, UTF-16 boundaries,
  whitespace, invalid database text and rollback. Minimal OpenAPI lint
  passed. The full backend suite was not rerun, and the existing Compose
  database was not migrated.
- Feedback may contain personal data. Moderation authorization, abuse
  prevention, account-deletion cleanup and retention policy remain necessary
  before real-user deployment; retry records still lack pruning and rotation.

## 2026-11-11 — Follow and block mutations

- The user chose to return the same not-found response for a follow blocked in
  either direction as for an unavailable user, and to allow new follows of
  frozen users without revealing their frozen status. Self-targets are invalid.
- Added dev follow, unfollow, block and unblock endpoints. Blocking atomically
  removes both follows and cancels pending invitations between the pair;
  unrelated invitations remain. Blocks are directional, and removing one does
  not restore prior follows or invitations. Current access and the absence of
  a block are rechecked before a follow retry is replayed.
- Mutations lock both users in PostgreSQL UUID order, then validate the session
  and claim the per-user retry key. Future social, invitation and account
  lifecycle writers need a compatible lock order. Session expiry after a
  blocked write rolls back the block, follow removals, invitation cancellations
  and retry claim together. No realtime invalidations are emitted.
- Targeted integration, concurrency, rollback and module-boundary tests passed,
  as did minimal OpenAPI lint. An initial full-suite run exposed a username
  collision between two test fixtures introduced by the invitation-preference
  tests; the new fixture was renamed and the full `:app:test` suite then passed.
  Chat filtering, invitation creation and enforcement, account-deletion cleanup
  and realtime delivery remain unimplemented.

## 2026-11-11 — Invitation responses and cancellation

- The user chose to recheck the sender's current host role, the recipient's
  follow and current invite preference. Mutual follows use the friends mode;
  otherwise the following mode applies. Both `open` and `request` permit
  responding to an existing request. The user chose immediate permanent
  cancellation when eligibility is lost, so a cancelled request is no longer
  available for decline and does not return if eligibility is restored.
- V19 cancels pre-existing pending rows that fail these requirements. Follow
  and preference mutations now cancel affected pending requests in their
  transaction. The read filters ineligible requests as a defense against
  inconsistent data. Future host-removal and lifecycle writers must implement
  equivalent cancellation atomically; none exist yet.
- Added retry-safe dev invitation responses. Pair user locks precede the
  session, table and invitation locks; acceptance inserts a membership, a
  system chat line and two outbox records atomically. Responses check current
  access before replay; an accepted response also requires current membership.
  Stale responses cancel the request and do not retain a retry claim. No
  invitation-creation service, chat filtering, gateway or outbox relay exists.
- The internal, provisional outbox entries are `table.member.joined.v1` with
  `{tableId,userId}` and `chat.message.created.v1` with
  `{tableId,messageId,kind:"system"}`. They are not public realtime messages:
  any future consumer must apply current authorization and filtering, and
  emit only the separately specified invalidation payload. Review their
  compatibility, retention and delivery semantics before implementing a relay.
- Targeted integration tests and a full `:app:test` run passed, including a
  populated V18-to-V19 migration and concurrent acceptance versus blocking.
  Minimal OpenAPI lint passed. The ordinary Compose database was not migrated.

## 2026-11-11 — Retry-safe inbox clear

- Added dev `DELETE /notifications`, which atomically deletes the authenticated
  user's existing inbox entries but leaves chat read positions and other
  users' entries unchanged. It locks the recipient before deleting, matching
  notification insertion and inbox mark-read ordering. A matching retry returns
  204 without deleting later arrivals; reusing the key for another operation
  returns 409. Session expiry rolls back the deletion and retry claim.
- Clearing removes the notification row's uniqueness key. A future event
  consumer must commit durable processed-event markers and all corresponding
  effects atomically, retaining markers across clear, cap eviction and
  compatible replay horizons; no notification consumer or relay exists yet.
  Inbox reads, delivery, cap enforcement and realtime invalidations remain
  unimplemented.
- Targeted inbox integration tests passed for isolation, retry, CSRF, lock
  ordering with insertion and expiry rollback. Minimal OpenAPI lint passed. No
  migration or ordinary Compose database change was made.

## 2026-11-11 — Inbox visibility and debt calculation

- The user chose to hide table notifications when normal table access ends,
  retaining separately authorized settlement notifications. For the delegated
  details, use reversible visibility, the prototype's directional chat block
  filter, and both-direction invitation blocking; bet and settlement notices
  are not suppressed merely by a block.
- The current inbox schema does not identify an invitation or a settlement
  receipt or person-net source. Stored text alone cannot establish access.
  Inbox retrieval remains unimplemented until source attribution and
  authorization can safely support pending invitations, direct joins,
  individual receipts and person-net changes; do not silently return an
  incomplete or overexposed inbox.
- Added a pure integer-cent debt calculator for negative table True balances
  and per-person open receipt nets. It uses arbitrary-precision intermediate
  sums, checks duplicate inputs and bounds, and implements exact-cap and 4fun
  decisions. It does not retrieve inputs or authorize cap changes. Future
  transactions must select complete current data, include retained open
  receipts from archived tables, and serialize against every operation that
  changes debt before enforcing this calculator.
- Targeted debt and table-settlement calculator tests and module architecture
  tests passed. No database or running Compose service was changed.

## 2026-11-11 — Dev debt read

- Added authenticated `GET /me/debt`. A single PostgreSQL statement checks
  the current session and gathers table exposure and open receipt inputs from
  one snapshot; the settlement module's calculator performs the final
  per-table and per-counterparty netting. It includes append-only corrections,
  excludes Active, No guess and 4fun wagers, and includes open receipts from
  archived tables. Paid and forgiven receipts are excluded; sent, disputed
  and forgiveness-requested receipts remain open.
- Table aggregate inputs now use arbitrary-precision integer cents so a large
  positive balance does not overflow before calculation. The published debt
  remains a signed 64-bit integer and fails explicitly if it cannot fit.
  The calculator is exposed through the settlement module's named API.
  A read snapshot does not authorize or serialize a later cap change; cap
  updates and proposal, pick and lock enforcement remain unimplemented.
- The full backend test suite and minimal OpenAPI lint passed. Tests include
  correction entries, no cross-table or cross-person offsets, archived receipt
  debt, excluded statuses and wagers, authentication, and overflow boundaries.
  No migration or ordinary Compose database change was made.

## 2026-11-11 — Retry-safe debt-cap updates

- Added dev `PUT /me/debt-cap`. A fresh update requires zero debt and accepts
  NULL or a nonnegative whole-point amount in cents up to 2147483600, matching
  the prototype's whole-point setting and the existing integer storage limit.
  Previously stored fractional-point values remain readable. Even an
  unchanged value is rejected with a fresh key when debt is nonzero.
- The transaction locks the user, checks the session and retry key, reads debt
  and updates the cap. A matching retry returns its original 204 without
  reapplying the update after debt changes; debt conflicts roll back the retry
  claim. The shared debt query retains arbitrary precision for this check, so
  debt greater than the GET response's int64 range still produces a conflict.
- Invitation acceptance already locks its affected user before table
  membership changes. All future debt-affecting writers must acquire every
  affected user lock in PostgreSQL UUID order before table, bet, ledger or
  receipt locks and read fresh state after acquiring them. The schema alone
  does not enforce that protocol. This update does not implement cap checks
  on proposal, pick or lock, or make the backend production ready.
- Targeted integration and module tests passed, including debt conflicts,
  large amounts, retry behavior, a real invitation-acceptance race, a
  cooperating debt-writer race and session-expiry rollback. Minimal OpenAPI
  lint passed. No migration or ordinary Compose database change was made.

## 2026-11-11 — Retry-safe reaction mutations

- Added dev PUT and DELETE `/messages/{messageId}/reaction`. The user chose
  emoji rather than arbitrary text and delegated the validation policy: PUT
  accepts exactly one RGI sequence using the pinned ICU4J 76.1 Emoji 16.0
  dataset. This deliberately rejects newer or non-RGI sequences, even when a
  device can render them; changing the dataset is an API behavior change.
- Both operations recheck the active account and session, current table
  membership, react permission and message visibility before retry replay.
  Text and GIF messages from authors blocked by the caller are hidden, while
  bet cards remain visible; system and settlement messages are not reactable.
  Missing and inaccessible messages share a 404. Each changed row writes a
  table-only internal outbox invalidation; no event relay, gateway, chat reads
  or message sending is implemented.
- The actor and session are locked before the table, membership and message.
  Blocks serialize through the actor's user lock. Future membership, archive,
  message, author-deletion and moderation writers must coordinate compatible
  user-before-table locking, recheck state after locks and prevent author
  deletion racing visibility checks. The database does not enforce this.
- The full backend suite, including integration and module checks, and minimal
  OpenAPI lint passed. Tests cover emoji sequences and invalid input, user
  isolation, replacement, no-ops, retries and conflicts, current permission,
  directional blocks, bet visibility, membership, archive and expiry rollback.
  No migration or ordinary Compose database change was made.

## 2026-11-11 — Chat read positions and notification synchronization

- Added dev GET and PUT `/tables/{tableId}/last-read`. GET uses one snapshot to
  return the visible unread count and first unread message, excluding the
  caller's own messages. PUT only advances to a currently visible message and
  never moves backward, including when a previously read message is deleted.
  Both require current membership in a non-archived table. The visibility
  predicate is shared with reaction authorization; chat windows and archive
  reads are still unimplemented.
- A fresh PUT marks the caller's existing chat notifications through the
  resulting position read, including hidden notifications. A retry rechecks
  current access and target visibility without reapplying. V20 changes the
  notification insertion trigger so later chat notifications for already-read
  messages start read; it leaves existing rows unchanged. Both paths serialize
  on the recipient's user row under READ COMMITTED. Future notification
  writers must preserve this lock order and fresh-snapshot behavior; realtime
  cross-session delivery and the notification consumer are not implemented.
- The full backend suite and minimal OpenAPI lint passed, followed by targeted
  tests for deleted-author and revoked-session cases. Tests cover block and
  bet visibility, forward-only positions, notification isolation, a forced
  insert-versus-read lock race, expiry rollback and a populated V19-to-V20
  upgrade. The migration was tested in disposable containers only; the
  ordinary Compose database was not changed.

## 2026-11-11 — Dev chat window reads

- Added `GET /chat/{tableId}/messages` for current members of non-archived
  tables, with a single-snapshot authorization and filtered projection. Latest
  returns up to 30 messages; `around` takes a visible message ID and returns up
  to five earlier and 25 from it onward; before/after pages contain up to 25.
  Items are oldest first. Bet cards have no picks and settlement cards contain
  static parties and amounts, not receipt status. Deleted users' reactions are
  suppressed; blocked users' reactions on otherwise visible messages remain
  visible as in the prototype.
- Before/after cursors encrypt the internal sequence with AES-GCM, using a
  domain-separated key derived from the existing dev idempotency secret. They
  are bound to user and table and survive deletion of the boundary message;
  replacing the secret invalidates them. Every nonempty window has a next
  cursor even at the current end, allowing clients to retain it and fetch
  later arrivals without gaps. Empty pages have no replacement cursor. Block
  changes can make earlier messages visible, so clients must refetch affected
  windows. There is no realtime invalidation yet.
- Targeted integration, chat contract, unread/reaction regression and module
  checks passed, as did minimal OpenAPI lint. Tests include 30/25/around
  boundaries, more than one page of new arrivals, deleted anchors, user/table
  cursor binding, tampering, visibility, all five message shapes and session
  revocation. No migration or ordinary Compose database change was made; chat
  sending, archive access and realtime delivery remain unimplemented.

## 2026-11-11 — Retry-safe chat sending

- Added dev `POST /tables/{tableId}/messages` for text and GIFs. The user
  delegated the text limit and chose the prototype GIF catalog: trimmed text
  accepts 1–2000 Unicode code points and valid PostgreSQL text; GIF IDs must
  match the 24 current catalog entries. The backend and frontend catalogs
  must be updated together. Text and GIF permissions are independently
  checked under the actor, session, table and membership locks.
- A send atomically creates the message, retry claim and internal
  `chat.message.created.v1` outbox event. V21 adds an optional resource UUID
  to the retry record rather than duplicating chat content. A matching retry
  rechecks access, permission and unchanged message content, then reconstructs
  the original 201 representation from the matching request and original
  timestamp, without later reactions. Removed or changed messages return 404
  instead of being recreated or exposing old content. Future moderation,
  deletion and edit paths must preserve these checks and compatible locking.
- The full backend suite passed, including validation boundaries, Unicode,
  permissions, retries, simultaneous sends, expiry rollback and a populated
  V20-to-V21 migration. Minimal OpenAPI lint passed. No ordinary Compose database was
  migrated. Event relay, notification creation, realtime delivery, rate
  limiting and HTTP request-size enforcement are not implemented.

## 2026-11-11 — Host-controlled member permissions

- Added dev `PUT /tables/{tableId}/members/{userId}/permissions` for bets, text,
  react and GIF permissions. Only a current host may change another current
  non-host member, including a frozen member. Changes are quiet: they add no
  system chat message. Host targets produce 409, non-host callers 403, and
  missing or inaccessible tables and unavailable or non-member targets 404.
  Chat and reactions enforce their respective permissions; bet authorization
  and enforcement of the bets permission are still unimplemented.
- The actor and target users are locked in PostgreSQL UUID order before the
  session, table and membership. A matching idempotent retry checks current
  authorization and target role but never reapplies the old value. An actual
  change records an internal table-and-target-only outbox event; a future relay
  must not disclose it to unrelated non-host members. There is no realtime
  delivery. No migration or ordinary Compose database change is required.
- Targeted integration, table-read, chat, reaction and architecture regression
  tests passed, as did minimal OpenAPI lint. Tests cover permission types,
  redaction, host and target authorization, frozen and deleted targets,
  retries and conflicts, a concurrent chat send and expiry rollback.

## 2026-11-11 — Retry-safe remainder rule changes

- Added dev `PATCH /tables/{tableId}/remainder`. A current host can select
  host, proposer, earliest or random. An actual change is rejected while any
  bet is Active; unchanged requests are no-ops, and Pending bet snapshots stay
  untouched. Matching retries recheck host access without reapplying earlier
  values. A rejected change rolls back its retry claim, allowing a later
  attempt once the bet is no longer Active.
- Actual changes atomically write a neutral system chat line and internal
  table/chat outbox events, without impersonating the caller as "You" to other
  members. No realtime relay is implemented. The table lock and a fresh Active
  bet query must be shared by future bet writers after their user locks; the
  database does not guarantee this coordination. Bet proposal and locking
  remain unimplemented. No migration or ordinary Compose database change is
  required.
- Targeted integration, table-read, chat-projection and architecture regression
  tests passed, as did minimal OpenAPI lint. Tests cover other-table Active
  bets, Pending snapshots, no-ops, authorized retries, conflicts, a forced
  Active-bet writer race and rollback on session expiry.

## 2026-11-11 — Host changes and attributable system chat

- The user chose to require another active host for step-down rather than
  counting frozen hosts, and requested named, deletion-safe host-change chat
  notices. Added dev host PUT/DELETE. Promotion clears member restrictions;
  demotion cancels that host's pending table invitations and keeps permissions
  enabled. Retry replay rechecks current host access, so a successful
  self-demotion cannot replay as a host until promoted again. Bet remainder
  snapshots and accepted invitations are not rewritten.
- V22 stores a constrained host-change event and subject UUID, not a username
  snapshot. Chat reads render the current username or `Deleted user` for a
  tombstone. Existing free-text system messages are unchanged and still need
  deletion-cleanup design. The migration is tested in disposable databases;
  the ordinary Compose database is not migrated.
- Host changes serialize on the table after ordered actor/target user locks.
  Future freeze, unfreeze, account deletion and membership writers must use
  compatible user-before-table locks and fresh state checks. Later freezes can
  still strand a table with no active host; recovery is unimplemented.
  Internal outbox events do not provide realtime delivery.
- Targeted host, invitation, chat, permissions and architecture tests passed,
  along with minimal OpenAPI lint. Tests include populated V21-to-V22 upgrade,
  tombstone rendering, invite cancellation, concurrent demotions and invite
  acceptance, a coordinated freeze race, retry behavior and expiry rollback.
