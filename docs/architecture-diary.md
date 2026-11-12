# Architecture diary

The earlier chronological diary is preserved in [the archive](archive/architecture-diary.md).
New entries are recorded here.

## 2026-11-12 — Individual-user lookup

- The user chose to keep names available for outgoing blocks, shared current
  tables and direct receipts despite block filtering in search. Mutual blocks
  do not change what the caller can see in their own block list.
- Deleted placeholders are limited to shared current or unexpired archived
  membership, direct receipts, and identities referenced in accessible
  historical chat. Hidden and absent identities share a 404. Future history
  and deletion implementations must define and preserve their own access paths.
- Search and lookup integration tests exercise visibility, receipt and archive
  boundaries, changed membership and revoked sessions. OpenAPI lint passes
  with two existing warnings. The shared database and Compose stack are untouched.

## 2026-11-12 — User search

- The user chose case-insensitive substring search, inclusion of frozen users
  without exposing their status, and hiding blocks in both directions. Search
  also excludes the caller and deleted identities.
- The dev endpoint uses literal substring matching and 50-item username-keyset
  pages. Pages are not a snapshot: renames, blocks and account changes can
  affect subsequent results. Individual-user lookup and rate limiting remain
  unimplemented; do not expose this development service to real users.
- A disposable-database integration test passed for filtering, substring matching,
  paging, changed visibility, validation and revoked sessions. OpenAPI lint
  passed with two existing warnings. The documentation preview served the
  updated roadmap successfully; the shared Compose stack was untouched.

## 2026-11-11 — Developer documentation

- The user chose the new guides and whole-project roadmap as the current
  authority; the original v3 plan and diary are historical. Remaining v3
  requirements still need reconciliation, not silent removal.
- The user chose a locally served dev-only `/docs/` site, dark presentation,
  local search, offline Mermaid diagrams, and a link to the draft OpenAPI file.
  Interactive API documentation is future work.
- Roadmap work may proceed in parallel when dependencies allow. Preserve shared
  databases and credentials; the historical reset commands are not routine
  setup. Stronger pre-lock pick privacy remains a proposed, unimplemented target.
- The site build and focused backend profile, architecture, and documentation
  tests passed, including real HTTP requests to bundled pages. Local search and
  Mermaid diagrams were checked in a browser without remote resource requests.
  A separate Docker image build passed; the shared Compose stack was untouched.
  Mermaid produces a non-fatal large-chunk build warning.

## 2026-11-11 — Table creation and membership requests

- The user selected prototype invite preferences: `open` adds immediately,
  `request` invites, and `closed` prevents adding. Ineligible recipients are
  reported generically without blocking eligible ones; frozen users are skipped.
- Requests are limited to 100 people. Creation returns the current table and
  original per-person outcomes on retry; later additions replay original
  outcomes without reapplying. Current access is rechecked.
- Direct additions produce a neutral table notice; pending invitations are
  not announced in table chat. Events are stored in the outbox, not delivered.
- Focused disposable-database tests covered creation, acceptance, retries,
  concurrent creation, invitation/block races, expiry rollback and upgrading
  populated migrations through V23. OpenAPI lint passed. The shared database
  was not migrated.

## 2026-11-11 — Table betting settings

- Inbox retrieval remains gated: safe invitation and settlement visibility
  requires source attribution and authorization, and a partial inbox would
  contradict the earlier decision.
- The user chose future-only effects for host-only proposals and the U2Pick
  switch, neutral chat notices for changes, and current-table retry responses.
  Existing bets are unchanged. Future proposal and Quick Pick writers must
  enforce the switches under the table lock.
- Focused disposable-database tests passed for authorization, existing bets,
  retries, neutral notices and concurrent partial updates. OpenAPI lint passed;
  no shared database migration or Compose rebuild was performed.

## 2026-11-11 — Table profile

- The user chose a neutral `Table details changed` chat notice rather than
  permanently recording the new name and emoji in chat. Existing names in
  other historical content are unaffected.
- Name and emoji updates use creation's validation and the current-table
  retry behavior. Pending invitations read the current table details; their
  future realtime invalidation must remain private and recheck eligibility.
- Focused disposable-database tests passed for profile updates, authorization,
  retries, pending invitation views, concurrency and shared-chat regressions.
  OpenAPI lint passed. The shared database was not changed.

## 2026-11-11 — Quick Pick controls

- The user chose prototype behavior: enabling Quick Picks also enables sharing,
  with neutral chat notices. Rotation retries return the current code rather
  than a possibly invalid previous response.
- Feature disabling disconnects both subscription directions; stopping sharing
  or rotating disconnects incoming subscriptions. Existing copies persist.
  Affected subscriber tables receive internal invalidations containing only
  their own table IDs. No event relay, subscription API, backfill or fan-out
  exists. Historical code reuse remains a design gate for subscriptions.
- Focused disposable-database tests passed for access, retries, collisions,
  reciprocal disconnects, copy preservation and expiration rollback.

## 2026-11-11 — Member removal

- The user chose to block removal while any bet is active or pending or a
  resolved non-4fun bet awaits points settlement, and to require demoting
  another host first. When blocked, the prototype offers a shortcut to the
  member's permission controls. It remains separate from the backend API.
- Removal uses a neutral chat notice and retains the prior chat read position.
  Bet and financial history remain, pending target invitations are cancelled,
  and matching retries do not remove a readded member. Internal events are not
  delivered; a future gateway must revoke removed members' access.
- Focused backend and prototype tests passed, including settlement guards,
  re-add retries, session-expiry rollback and the permissions shortcut.
  The shared database and Compose stack were not changed.

## 2026-11-12 — Table deletion and archives

- The user chose a separate archive list and existing chat-window access for
  members at deletion. Access and listing expire after 90 days (2160 hours);
  data is retained, not deleted, pending a separate cleanup policy.
- Deletion blocks unsettled bets, snapshots members, cancels pending invites
  and disconnects Quick Pick subscriptions without deleting copies or
  financial records. User locks precede the table lock; membership races fail
  with a retryable conflict. Matching retries recheck host access.
- The read-only archive reuses current block filtering. Account deletion,
  realtime revocation, receipt actions and retained-data cleanup remain future
  work.
- Focused archive, chat, membership, Quick Pick and architecture tests passed,
  including membership-race and session-expiry rollback tests. OpenAPI lint
  passed. The full app suite ran 158 tests with two unrelated failures: a chat
  response is checked against the table-creation schema, and separate fixtures
  reuse Quick Pick code `Y4R7QA`. The shared database and Compose stack were
  untouched.

## 2026-11-12 — Personal profiles

- The user chose to release old usernames immediately, preserve active sessions
  after a rename, and return the current profile on an authorized retry.
- Usernames are normalized before validation and uniqueness checks; login uses
  the same whitespace normalization. Updates emit an internal user invalidation
  without name or hue. Attributed host messages resolve the new username;
  existing free-text history is not rewritten. No realtime delivery exists.
- Focused disposable-database tests passed for login, normalization, unique-name
  races, partial updates, retry safety, frozen accounts and expiry rollback.
  OpenAPI lint passed. The shared database and Compose stack were untouched.
