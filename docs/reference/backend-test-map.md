# Test guide

Run frontend tests with `npm --prefix client test`. They cover backend session,
table-read, profile lookup and privacy-aware bet-read handling as well as the retained browser-local prototype's
presentation, storage isolation, betting calculations, chat unread state,
settlement warnings and error toasts.

Run backend tests from `backend/` with `./gradlew :app:test`. Integration tests
use disposable PostgreSQL, Redis and Kafka containers; they do not target the
shared development database.

- `P2PicksApplicationTest` exercises authenticated dev HTTP flows, database
  upgrades, chat visibility and read positions, and internal betting and
  settlement transactions. Gated HTTP adapters are registered only in tests.
  The opt-in `bettingPreviewRunsAgainstIsolatedBackend` and
  `settlementPreviewRunsAgainstIsolatedBackend` tests also run the React
  previews over HTTP against those containers; their invocation and
  prerequisites are in the [frontend README](../../client/README.md). These
  use jsdom, not a browser.
- `BettingLifecycleGateTest`, `BetLockWorkerTest` and `BetLockSchedulingTest`
  cover the readiness gate and opt-in lock recovery.
- The bets and settlement module tests cover pure payout, U2Pick settler,
  debt and table-settlement calculations.
- Contract tests validate draft OpenAPI and realtime shapes; they do not imply
  that every drafted operation is implemented.
- Storage and `ProcessedEventStorageTest` cover database invariants and
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
