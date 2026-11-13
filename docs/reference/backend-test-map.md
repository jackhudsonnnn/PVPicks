# Test guide

Run frontend tests with `npm --prefix client test`. They cover backend session,
table-read, profile lookup and privacy-aware bet-read handling as well as the retained browser-local prototype's
presentation, storage isolation, betting calculations, chat unread state,
settlement warnings and error toasts.

Run backend tests from `backend/` with `./gradlew test` (all projects) or
`./gradlew :app:test`. Integration tests use disposable PostgreSQL, Redis and
Kafka containers, started once per run; they do not target the shared
development database. Test classes live in the same package as the code they
cover.

- Feature integration tests extend `IntegrationTestSupport`, which owns the
  containers, the single shared Spring context and common HTTP helpers
  (`loginAs`, `csrf` and request builders). Do not change the context
  configuration in a subclass; that would start a second context.
  - `security.AuthIntegrationTest`: login, CSRF/origin, session expiry and
    role-based route access.
  - `users`, `social`, `chat`, `feedback`: profile, search/lookup,
    relationships, invitations, chat windows, sending, reactions and read state.
  - `tables.TableMembershipIntegrationTest` and
    `tables.TableSettingsIntegrationTest`: creation, members, hosts, archives,
    reads, profile, settings, permissions, remainder and Quick Pick controls.
  - `bets`: `BetLifecycleIntegrationTest` (reads, end-to-end U2Pick flow and
    HTTP adapters), `BetProposalIntegrationTest`, `BetPickIntegrationTest`,
    `BetLockIntegrationTest` and `BetResolutionIntegrationTest`.
  - `settlement`: `PointsSettlementIntegrationTest`, `ReceiptIntegrationTest`
    (receipts, groups and balance-change reviews) and `DebtIntegrationTest`.
  - `MigrationIntegrationTest`: schema constraints and populated V2–V25
    upgrades. `PlatformIntegrationTest`: health, developer docs and
    infrastructure connectivity.
  - `bets.BettingFrontendIntegrationTest` and
    `settlement.SettlementFrontendIntegrationTest`: opt-in runs of the React
    app over HTTP against the containers. The betting run creates and picks,
    lets the backend lock the bet, then calls the result and settles points.
    Their invocation and prerequisites are in the
    [frontend README](../../client/README.md). These use jsdom, not a browser.
- `bets.BetLockWorkerTest` and `bets.BetLockSchedulingTest` cover the lock
  worker and its recovery; integration tests turn the worker off and lock
  bets explicitly. `settlement.SettlementGroupControllerTest` checks the
  pending-review list's identity rules and contract.
- Plain unit tests cover the pure payout and U2Pick settler (`bets`) and debt
  and table-settlement (`settlement`) calculators; `shared/kernel` tests cover
  `Money`.
- `ModuleArchitectureTest` verifies the Modulith modules, their declared
  dependencies and acyclicity, that shared modules and the kernel do not
  depend on features, and that calculators stay pure Java. Negative fixtures
  under `com.p2picks.archfixture` prove violations are detected.
- `sports`: `BallDontLieFeedTest` (provider samples mapped to the canonical
  schema; API key, date and rate-limit handling against a local HTTP server)
  and `SportsIntegrationTest` (scripted-feed polling, one event per changing
  poll, failure recording, ticker scope across a daylight-saving date, 503 when
  stale, `GET /ticker` against the OpenAPI schema).
- `delivery`: `OutboxCleanupIntegrationTest` (only old published rows are
  deleted), `OutboxRelayIntegrationTest` (publish after broker
  acknowledgement, retry after a broker failure), `RealtimeConsumerTest` (which
  events reach which feeds, amount-free envelopes, stable event IDs) and
  `RealtimeIntegrationTest` (a real STOMP client: CSRF on CONNECT, origin,
  member-only and own-feed subscriptions, rejected SEND, delivery rechecks after
  removal, session revocation, private settlement delivery). Integration tests
  turn off the scheduled relay and call it directly, and read the topic from the
  earliest offset so the consumer cannot miss an event while joining.
- Contract tests validate draft OpenAPI and realtime shapes; they do not imply
  that every drafted operation is implemented.
- Storage tests and `ProcessedEventStorageTest` cover database invariants and
  transactional event deduplication.

Keep UI and browser-only behavior in frontend tests. Add backend tests for
server authorization, concurrency, persistence, retry safety and financial
invariants when integrating each workflow. In particular:

- Exercise sports-feed validation, freshness, chronology, overtime and result
  handling independently from shared payout and settlement rules.
- Test Quick Pick subscription authorization, non-chaining, source result
  propagation and host wash when those flows are implemented.
- Cover account deletion, host handoff, tombstoned identities and cleanup when
  account deletion is implemented.
- Extend the realtime tests whenever a new event gains a destination, including
  its audience, privacy and revocation.
