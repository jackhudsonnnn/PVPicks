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
  - `bets.BetIntegrationTest` and `settlement.SettlementIntegrationTest`:
    internal betting and settlement transactions, debt and debt caps, and the
    gated HTTP adapters, which `GatedBetAdapters` and `GatedSettlementAdapters`
    register only in tests.
  - `MigrationIntegrationTest`: schema constraints and populated V2–V25
    upgrades. `PlatformIntegrationTest`: health, developer docs and
    infrastructure connectivity.
  - `FrontendPreviewIntegrationTest`: opt-in runs of the React betting and
    settlement previews over HTTP against the containers. Their invocation and
    prerequisites are in the [frontend README](../../client/README.md). These
    use jsdom, not a browser.
- `bets.BettingLifecycleGateTest`, `settlement.SettlementLifecycleGateTest`,
  `bets.BetLockWorkerTest` and `bets.BetLockSchedulingTest` cover the
  readiness gate and opt-in lock recovery.
- The `bets` and `settlement` module tests cover pure payout, U2Pick settler,
  debt and table-settlement calculations; `shared/kernel` tests cover `Money`.
- `ModuleArchitectureTest` checks Modulith modules and ArchUnit boundaries,
  including negative fixtures under `com.p2picks.archfixture`.
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
- Test realtime subscription authorization, revocation, deduplication and
  reconnect recovery before enabling delivery.
