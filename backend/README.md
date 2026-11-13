# Backend

This is an early development-only Spring Boot skeleton, not a functional P2Picks
backend. The Phase 0 API and realtime contracts are still drafts. The database
schema contains users, authentication sessions, follow/block relationships,
tables, memberships, invitations, Quick Pick subscriptions, bets, participation,
picks, ledger entries, debt caps, settlement history, chat,
feedback and admin flag/strike storage, an outbox and processed-event markers.
Migration V25 removes the obsolete notification tables and their data. Flyway
applies pending migrations automatically at startup; do not start an updated
backend against a shared database without explicit approval.
Dev-only provisioning, login, logout, CSRF, `/me`, personal profile updates, table, settings, debt and
relationship and invitation reads, invitation preference
updates, debt-cap updates, feedback submission, follow/block mutations,
invitation responses, table creation, member requests and removal, table deletion
and 90-day read-only chat archives, table profile,
host/permission/remainder and betting-setting changes, Quick Pick feature/sharing
controls and code rotation, privacy-filtered bet reads, reaction mutations, chat windows,
text/GIF sending and unread-state reads and updates, and an admin health snapshot exist.
Many other mutations, public bet-resolution APIs and LAN/production
deployment do not. Do not expose this service
to a network or use it for real users; dev login does not yet have rate limiting.
The dev security filter restricts `/admin` and `/admin/**` to active admins
and player APIs to active players, checking the current database role on each
request. Both roles can use login, logout and `GET /me`. Existing memberships
are retained; no application API changes an account's role, and future APIs
must not introduce role changes. Migration V24 rejects ordinary database role
updates without changing existing accounts. Privileged database operators can
bypass the guard and can inspect or modify plaintext picks. No other admin
API is implemented.

Internal U2Pick proposal and resolution and shared pick, bet-lock and host
points-settlement transactions enforce authorization; proposal, pick and
lock also enforce debt caps. They snapshot the remainder, post resolution
deltas and create settlement receipts. Shared internal receipt history, line
actions and atomic person-group actions also exist. These services are not
exposed as APIs; event delivery remains unimplemented, so this is not an
available betting workflow. Proposal, pick/clear, U2Pick resolution and shared
host points-settlement and individual/group receipt HTTP adapters are behind
a readiness marker with no application bean; integration tests explicitly
register the adapters. Do not register the marker until the complete betting
and receipt workflow is safe.

The frontend's current read-only preview uses the backend session and table
reads. In local Vite development, `/api` is proxied to `127.0.0.1:8080` and
the backend must be configured with `PVPICKS_DEV_ORIGIN=http://127.0.0.1:5173`.
This does not change the default backend origin or make the dev backend safe
for network exposure. Do not restart it against a shared database without
approval; startup applies pending migrations.

The gated receipt workflow records a separate balance-change review per person.
Sending or requesting forgiveness requires acknowledging the current revision;
unreviewed warnings remain readable after receipts close. A later net change
refreshes pending reviews without undoing the other person's acknowledgment.
The gated pending-review list discovers those warnings independently of
receipt-group net totals; it is not a notification or event-delivery service.

An automatic U2Pick lock worker exists but is **disabled by default**. Setting
`PVPICKS_BET_LOCK_WORKER_ENABLED=true` for a dev backend enables an immediate
overdue scan and subsequent scans five seconds after each completed scan.
Enabling it mutates existing overdue bets in the connected database; do not
enable it against shared data without approval. It logs failures and retries
on later scans. Sports modes are deliberately not auto-locked until their
feed-specific lifecycle exists. No operator dashboard or alerting exists.

`GET /users?query=...` searches visible usernames by literal substring, in
50-item keyset pages. It hides the caller, deleted users and blocks in either
direction; frozen users remain visible without status. Results are not a
snapshot; rate limiting is not implemented. `GET /users/{id}` also resolves
the caller's blocked users, current table peers and direct receipt parties.
Deleted placeholders require an authorized shared membership, unexpired
archive, direct receipt or historical chat reference. A blocked or unrelated
deleted identity is indistinguishable from a missing user where not authorized.
Account deletion remains unimplemented; receipt-history HTTP access is gated.

`PATCH /me/profile` accepts username and/or hue with a valid idempotency key.
Usernames are trimmed and lowercased before validation and checked uniquely in
PostgreSQL. Renames release the old name, affect future login and leave existing
sessions active. Matching retries return the current profile; historical
free-text messages are not rewritten. Internal outbox events are not delivered.

## Application layout

The `app` Gradle project contains the development application and HTTP layer:

- `app/src/main/java/com/p2picks/app/`: application entry point.
- `app/src/main/java/com/p2picks/app/controllers/`: HTTP endpoints.
- `app/src/main/java/com/p2picks/app/services/`: application services.
- `app/src/main/java/com/p2picks/app/security/`: dev sessions, authentication
  and request security.
- `app/src/main/java/com/p2picks/app/config/`: development profile and migration
  configuration.
- `app/src/main/java/com/p2picks/app/support/`: shared queries, chat helpers
  and idempotency support.
- `app/src/main/resources/`: application configuration and database migrations.
- `app/src/test/`: app, contract, architecture and storage tests.

The Java folders under `app/` are organizational only: their classes all remain
in the `com.p2picks.app` package and share package-private access. They are not
separate domain modules.

The shared kernel contains a `Money` value in integer cents. Decimal point
conversion is exact and rejects fractions of a cent or values outside `long`;
arithmetic rejects overflow. Signed values support ledger deltas. Wager limits
and other domain rules are not implemented by this value type.

The eleven `modules/` Gradle projects are mostly empty domain skeletons. The
bets and settlement modules expose pure payout, U2Pick settler-rule,
table-settlement and debt calculators, not transactional services. Payout and
table settlement are shared by U2Pick and sports modes; proposal validation and
outcome authorization remain mode-specific. Their
`com.p2picks.modules.<module>.api` packages are named Spring Modulith
interfaces; `internal` packages are private. Declare an explicit Gradle
dependency when one module needs another module's API. Subpackages of `api`
must also be explicitly exposed as named interfaces to be used across modules.
The architecture tests check module discovery, dependency cycles and API-only
access; domain modules cannot depend on `app`, and the shared kernel cannot
depend on either. Current dev authentication remains in `app`; it has not
yet been moved behind the identity module API.

The application requires Java 21 and starts only with the explicit `dev`
profile. The current development profile binds to `127.0.0.1`. The `lan` and
`prod` profiles are deliberately rejected until their security and deployment
configuration exist.

## Developer documentation

The dev backend serves the handbook at `http://127.0.0.1:8080/docs/`.
It is unauthenticated whenever the dev profile runs, so do not expose the dev
backend to a network. LAN and production remain disabled.

From the project root, install the pinned documentation dependencies once:

```sh
npm ci --prefix docs
```

Gradle builds the site into the backend resources; Node.js 22.12 or newer is
required. The Compose image uses a separate Node build stage and a root build
context restricted by `.dockerignore` to the required source files; it excludes
environment files, credentials, dependencies and generated output.
The [source map](../docs/README.md) describes the current guides and references.
Interactive API documentation is planned;
the bundled OpenAPI file remains a draft.

On this machine the approved project-local JDK can be selected with:

```sh
export JAVA_HOME="$PWD/.tools/jdk-21/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$PWD/.tools/gradle"
```

Run these commands from `backend/`:

```sh
./gradlew :app:test
```

The infrastructure integration test requires Docker. The same task checks
Spring Modulith and ArchUnit boundaries, including negative test fixtures.
The Gradle wrapper downloads its pinned
distribution on first use; dependencies are resolved from Maven Central.
The same test task validates selected draft OpenAPI and realtime schema
examples; those checks do not imply that the APIs or realtime gateway exist.

The bootstrap Compose file starts PostgreSQL, Redis, Kafka and the skeleton.
Kafka UI is opt-in. Most frontend integrations and some migrations are not implemented.
The table schema does not enforce host continuity or invitation authorization:
host demotion checks continuity, but future transactional writers must also
enforce the relevant invariants.
Subscription authorization and copy fan-out are also unimplemented. The outbox
and processed-event markers are storage only: there is no event publisher,
relay or consumer.
Quick Pick controls require a current host and an idempotency key. Enabling the
feature enables sharing with its existing code; disabling it disconnects both
incoming and outgoing subscriptions. Stopping sharing or rotating the code
disconnects incoming subscriptions, preserving existing copies. Matching
rotation retries return the current code. Historical codes are not permanently
reserved; safe reuse must be addressed before subscription APIs are added.
Table deletion snapshots members, disconnects subscriptions, cancels pending
invitations, and blocks Active/Pending bets or resolved non-4fun bets awaiting
points settlement. `GET /tables/archives` and the existing chat window allow
snapshot members read-only access for 90 days (2160 hours), with block filtering.
Thereafter the archive is hidden but data remains; cleanup and account-deletion
policies remain unfinished. Receipt action HTTP access is gated; realtime delivery
is not implemented.
`GET /tables` and `GET /tables/{id}` return only current, non-archived table
memberships. Hosts see the Quick Pick code and all disabled permissions;
other members see only their own disabled permissions and no code. Missing,
archived and non-member tables all return 404. These reads do not expose bets
or picks; archives are listed separately.
`DELETE /tables/{id}/members/{userId}` requires a current host and another
non-host member. Removal is blocked for Active or Pending bets and resolved
non-4fun bets awaiting points settlement. Existing financial records and chat
read positions remain. A matching retry does not remove a readded member.
The prototype offers permission controls when removal is blocked; it is not
connected to this API. Realtime access revocation remains unimplemented.
`PUT /tables/{id}/members/{userId}/permissions` lets current hosts quietly
enable or disable a current non-host member's bets, text, react or GIF
permission. Frozen members remain eligible targets. Host targets return 409;
non-host callers receive 403 and missing or inaccessible tables and targets
return 404. Chat and reaction mutations enforce their permissions; internal
bet proposal, pick and lock transactions also check bets permission, but no
bet mutation APIs exist.
`PATCH /tables/{id}/remainder` lets a current host change the remainder rule
only when no bet is Active; an unchanged rule is a no-op even if a bet is
Active. Existing Pending bets keep their lock-time snapshots. A change writes
a system chat message and internal outbox events; no realtime delivery exists.
The internal bet proposal and lock transactions use the same table lock; no
proposal API is implemented. The optional worker is described above.
`PUT`/`DELETE /tables/{id}/hosts/{userId}` let current hosts promote or
demote current members. Promotion clears their restrictions; demotion cancels
their pending invitations from that table and requires another active host.
A retry after self-demotion returns 403 unless the caller is a host again.
V22 adds deletion-safe attribution for new host-change system messages:
reads use the subject's current username, or `Deleted user` after tombstoning.
Older unattributed system messages are unchanged. Later account freezes may
still leave no active host; recovery and account deletion are unimplemented.
The bet schema does not implement transitions, pick authorization, privacy or
debt-cap enforcement. The payout calculator is not connected to bet resolution.
Ledger storage enforces append-only, zero-sum entries per bet. Internal
settlement and receipt transactions exist; their HTTP adapters remain gated.
`GET /me/debt` computes the caller's debt from one database snapshot, including
open receipts from archived tables. `PUT /me/debt-cap` requires zero current
debt and stores a whole-point limit or NULL under the user lock. Future debt
writers must acquire compatible user locks before reading or writing debt;
this is not enforced by the schema. Internal proposal, pick and lock enforce caps.
`GET /me/debt-cap` reads the current user's saved setting and default.
The invitation-preference PUT, debt-cap PUT,
`POST /feedback`, `POST /invites/{id}/respond`
and follow/block POST/DELETE, reaction PUT/DELETE, chat message POST, chat last-read PUT
member-permission PUT, remainder PATCH and host PUT/DELETE
have HTTP retry protection; other mutations do not. Each requires an
`Idempotency-Key` of 1–255 characters and atomically stores a keyed request
fingerprint with the update. A matching retry returns the original bodyless 201
or 204, or the original chat-creation representation, without reapplying;
reusing the key for a different request returns 409. Current authentication is
rechecked. Records are not pruned, and account-deletion cleanup and key rotation
are not implemented; this is not ready for real users. The draft minimum window
is 24 hours after commit; after that, reconcile state rather than retry blindly.
Reaction PUT/DELETE requires current table membership and react permission,
and accepts only visible text, GIF and bet messages. The caller's blocks hide
text and GIF messages, but not bet cards. PUT accepts exactly one RGI emoji
sequence from ICU4J 76.1 (Emoji 16.0); newer and non-RGI sequences may be
rejected. Changed reactions create an internal table-only outbox invalidation;
realtime delivery remains unimplemented.
`GET /chat/{id}/messages` returns filtered windows in stable order: the latest
30, up to 30 around a visible message ID, or up to 25 before/after an opaque
cursor. A nonempty page always supplies a next cursor, including at the end,
so clients can retain it and poll for new arrivals without gaps. Cursors are
encrypted and bound to the current user and table; the cursor key is derived
from the dev idempotency secret, so changing that secret invalidates cursors.
Bet cards contain no picks and settlement cards contain only static amounts
and parties. Deleted users' reactions are excluded; reactions from blocked
users remain visible on otherwise visible messages, matching the prototype.
`POST /tables/{id}/messages` sends text or a GIF when the caller has the
corresponding permission. Text is trimmed and limited to 2,000 Unicode code
points. GIF IDs are restricted to the current prototype catalog; update both
catalogs together when it changes. Matching retries return the original 201
message after checking current access and permission. If the original message
was deleted or its content changed, the retry returns 404 rather than exposing
the old content or recreating it. V21 stores a
message-ID reference, not a second copy of the message text, in the retry row.
Sending writes an internal outbox event but does not deliver chat.
Rate limiting and HTTP request-size enforcement are not
implemented.
`GET /tables/{id}/last-read` returns the filtered unread count and first unread
message for current members. `PUT /tables/{id}/last-read` advances monotonically
to a visible message. Last-read endpoints do not support archives.
Invitation responses revalidate the sender's host status, the recipient's
follow and current preference, and blocks. Losing eligibility cancels a
pending invitation permanently; preference and follow changes enforce this
immediately, and V19 cancels existing ineligible rows. Acceptance adds a member
and a system chat message. Table member requests can create invitations and
must preserve these invariants and compatible locking; host demotion cancels
its pending invitations.
The acceptance outbox records are internal drafts, not realtime messages;
there is no relay or consumer.
`POST /feedback` stores validated guide feedback; moderation access, rate
limiting and personal-data cleanup are not implemented. Existing feedback links
predating V18 are retained but not validated against its new constraint.
Report and audit storage and retention policies are still open.
Admin flags and strikes are personal moderation data. Storage does not enforce
authorization, automatic freezes, session revocation or account-deletion cleanup.
Future sensitive admin operations must recheck authorization as part of their
transaction; a request-level check alone cannot prevent an in-flight role or
session change.
`GET /admin/health` reports PostgreSQL and Redis indicator statuses without
connection details. Kafka is explicitly `NOT_CHECKED`, and latency and error
metrics are not provided. Authentication depends on PostgreSQL, so the endpoint
may itself be unavailable during a database outage.
From the project root:

```sh
./scripts/provision-dev.sh --init
docker compose -f infra/compose/docker-compose.dev.yml up --build
```

In another terminal, after the backend has started and applied migrations:

```sh
./scripts/provision-dev.sh --provision
bash scripts/seed-dev.sh --seed
```

The provisioning script generates separate signing and retry-fingerprint keys
and four account passwords once, then provisions the password hashes into
PostgreSQL using BCrypt. Re-running provisioning verifies
that existing accounts still match rather than overwriting them. The signing key
is in `infra/compose/.env.auth`, the retry key is in
`infra/compose/.env.idempotency`, and the plaintext dev account credentials are
in `infra/compose/.env.dev-accounts`. These files are ignored, owner-only and
local: do not share, commit or use them for LAN/production. Losing the account
file means the generated passwords cannot be recovered from their hashes.
Keep the retry key consistent with its database: after a key has been used,
losing or replacing it makes protected mutations fail closed. Restoring the
original key is required; automatic rotation and recovery are not implemented.
Running `--init` after loss generates a new key, but does not restore access to
the old retry records.
Provisioning requires `openssl`, Docker Compose and permission to install
PostgreSQL's `pgcrypto` extension.

The explicit seed command targets the development Compose PostgreSQL service,
after migrations and provisioning. It creates two sample tables and two active
U2Pick bets for the intermediate and pro accounts; newbie starts without a
table. It is not a full copy of the frontend's mock profiles or historical
load-test dataset. Seeds are written once in a transaction; later runs do not
restore changed or deleted samples. Missing accounts or conflicting Quick
Pick codes fail without partial seed data. The marker is stored in the dev
database, so a new empty volume can be seeded again. Never run this command
against LAN, production or a database containing real-user data.
The frontend's read-only preview can list these tables for a provisioned member.

The browser must use the configured same origin (by default
`http://127.0.0.1:8080`). First GET `/auth/csrf`, retaining its HttpOnly CSRF
cookie, then send its JSON `token` as `X-CSRF-TOKEN` along with the same-origin
`Origin` header for POST `/auth/login`. Login uses a provisioned username and
password and sets an HttpOnly, SameSite=Strict session cookie. Fetch a new CSRF
token after login and logout. GET `/me` uses the session cookie; POST
`/auth/logout` revokes that session. Dev sessions expire after eight hours
without refresh. These cookies do not have `Secure` because dev uses local HTTP;
TLS, secure cookies, rate limiting and refresh tokens are required before LAN.
The frontend uses these session endpoints via its local Vite proxy; its
remaining prototype workflows are not exposed by the current entry point.

To include Kafka UI, add `--profile tools` before `up`:

```sh
docker compose -f infra/compose/docker-compose.dev.yml --profile tools up --build
```

The backend and unauthenticated Kafka UI are bound to the host's loopback
interface. Set `PVPICKS_PORT` and `PVPICKS_KAFKA_UI_PORT` to choose different
host ports (defaults: 8080 and 8081). Do not expose Kafka UI to a network.
Kafka UI has no authentication and permits cross-origin requests; use it only
with disposable local development data and stop it when not needed.
Stop the stack with the same Compose file and `down`. This Compose setup is
for local development only.

Compose requires `PVPICKS_DB_PASSWORD` in `infra/compose/.env` or the shell.
The local `.env` is ignored and must not be shared. PostgreSQL and Kafka use
project-scoped named volumes; `down -v` deletes their data. The backend health
endpoint checks PostgreSQL and Redis; Kafka has a separate Compose health check.
Flyway applies versioned migrations on startup in the dev profile only; the
database role needs permission to install PostgreSQL's `citext` extension.
Flyway does not automatically baseline a pre-existing nonempty schema: back up
and inspect an existing database before deciding how to migrate it. Future
case-insensitive username queries should normalize their parameters or cast
them to `citext`, because JDBC binds strings as `varchar`.
Follow and block rows are directional, unique, reference existing users and
reject self-relations. `GET /me/relationships` returns only the caller's own
blocks and suppresses follows across blocks and relations to deleted users.
The dev follow/block mutations serialize each pair, and blocking removes both
follows and cancels pending invitations in either direction. A blocked follow
attempt returns the same 404 as an unavailable user; frozen users can still be
followed or blocked. Unblocking does not restore prior follows or invitations.
These operations do not emit realtime events; account-deletion cleanup is not
implemented. Direct database
edits are not a substitute for the block API.
`GET /me/invites` lists visible pending invitations without granting table
access. `GET /me/invite-preferences` returns saved preferences or the default
friends `open` and following `request`. `PUT /me/invite-preferences` updates
those settings and cancels ineligible pending invitations. Table creation and
host member requests add or invite eligible recipients according to these
preferences; their internal outbox events are not delivered.

To run `:app:bootRun` outside Compose, supply `PVPICKS_DEV_JWT_SECRET` from
the ignored `.env.auth` and `PVPICKS_DEV_IDEMPOTENCY_SECRET` from the ignored
`.env.idempotency`, along with `PVPICKS_DATASOURCE_URL`,
`PVPICKS_DB_USER`, `PVPICKS_DB_PASSWORD`, `PVPICKS_REDIS_HOST` and
`PVPICKS_KAFKA_BOOTSTRAP_SERVERS` for reachable development services, then pass
`--args='--spring.profiles.active=dev'`. Compose does not publish those service
ports to the host. If the browser origin differs from the default, set
`PVPICKS_DEV_ORIGIN` to the exact HTTP loopback origin, including its port.
