# Kafka and Redis opportunities

An audit of where Kafka and Redis should, and should not, take on more work.
It is prioritized for the next milestones: the LAN rehearsal and a first
production deployment with a few backend instances. Each item is marked
**Built** or **Planned**; planned items are not implemented.

The ground rules don't change: PostgreSQL is authoritative, Kafka carries
committed events (ADR 0002), and Redis is disposable (ADR 0003). Nothing here
moves money, picks, debt or sessions out of PostgreSQL.

## Where things stand

- **Kafka** carries one stream: `delivery.OutboxRelay` publishes committed
  outbox rows to `p2picks.outbox.v1` (3 partitions, keyed by aggregate).
  `delivery.RealtimeConsumer` is the only consumer. Each instance reads every
  event in its own consumer group and turns it into WebSocket invalidations.
- **Redis** backs the login throttle and the ticker cache (both below). It is
  not used for delivery, sessions, locks or idempotency.
- **Background work** is `@Scheduled` polling on every instance: the outbox
  relay (500 ms), the lock worker (5 s), sports resolution (30 s), the
  scores poller (60 s), the stats poller (20 s), the realtime session sweep
  (15 s), outbox cleanup (daily) and, in dev, the demo bots and games.

## Priorities

| # | Change | Tool | Status | Why now |
|---|---|---|---|---|
| 1 | Throttle failed sign-ins | Redis | **Built** | Each attempt costs a BCrypt-12 hash; there was no limit on password guessing |
| 2 | Cache the ticker | Redis | **Built** | Every client refetches the same query on each feed change |
| 3 | One runner per scheduled job, shared feed budget | Redis | Planned: before a second instance | Two instances double BallDontLie calls against a 5-a-minute plan |
| 4 | Rate limits on public and chatty endpoints | Redis | **Built** | Chat, search, feedback and proposals had no limit |
| 5 | Metrics, logs and outbox/consumer lag | OpenTelemetry, not Kafka | Planned: before production | No visibility into relay lag or worker failures |
| 6 | Event-driven sports resolution | Kafka | Planned | Replaces a 30-second scan of every pending sports bet |
| 7 | Topic layout and partition count | Kafka | Planned: before production | Partitions cap consumer parallelism and can't shrink later |
| 8 | Raw sports ingest stream, analytics sink, more caches | Kafka, Redis | Later, when measured | Useful, but not yet a bottleneck |

The [last section](#not-recommended) lists requests from the brief that
don't fit this codebase, with the better option for each.

## Events versus requests

Use an **event** when the producer doesn't need the result and the work can
happen after commit. Keep a **request** (or one transaction) when the caller
needs the answer, or the effect must commit atomically with the change.

| Flow | Treat as | Reason |
|---|---|---|
| Proposals, picks, locks, results, ledger, points settlement, receipts | Request, one PostgreSQL transaction | Debt caps, payouts and ledger must commit together (ADR 0001) |
| Bet and settlement cards posted to chat (`chat.ChatMessageWriter`) | Same transaction | The card must exist exactly when the bet or settlement does |
| Account freeze and deletion (`accounts.AccountDeletionService`) | Request | The cleanup across modules must be all or nothing |
| Login, logout, idempotency claims | Request | The caller needs the result; ADR 0003 keeps the records in PostgreSQL |
| Realtime invalidations | Event (built) | Best effort, after commit, fan-out to many sockets |
| Sports bet resolution after a feed change | Event (planned, #6) | Triggered by data the bet doesn't own, and can lag by seconds |
| Raw provider responses | Event (later, #8) | Ingest and processing scale and fail independently |
| Product analytics, audit trails | Event (later, #8) | Read-only consumers of the existing outbox stream |

## Redis

### 1. Login throttle — Built

`security.LoginThrottle`, called from `AuthenticationController.login` (admin
passwords) and `.code` (player sign-in codes).

Only failed sign-ins count, so legitimate users are never slowed down. By
default, 10 failures for one account name or 50 from one client address,
within 15 minutes, block further attempts for that name or address until the
window ends. Blocked attempts get 429 `rate_limited` with `Retry-After`, even
with the right password. A success clears the account name's count. Counters
use one atomic Lua `INCR`+`PEXPIRE`, and keys hold a SHA-256 of the name or
address. Configure with `PVPICKS_LOGIN_THROTTLE_PER_ACCOUNT`,
`PVPICKS_LOGIN_THROTTLE_PER_ADDRESS` and `PVPICKS_LOGIN_THROTTLE_WINDOW`.
Wrong sign-in codes count per player, keyed by the player's id (or a hash of
the typed name when it matches nobody), 10 per window
(`PVPICKS_LOGIN_THROTTLE_CODES_PER_PLAYER`), with a per-address backstop of
100 (`PVPICKS_LOGIN_THROTTLE_CODES_PER_ADDRESS`); a right code clears the
player's count. Admins list the counters and clear one player or all with
`GET`/`DELETE /admin/sign-in-lockout` and `DELETE
/admin/sign-in-lockout/players/{playerId}`.

**Why Redis.** Counters with expiry, shared by every instance, written on the
failure path only, with no PostgreSQL write amplification.

**When Redis is down.** The throttle fails open and logs a warning. Sign-in
still works against PostgreSQL, with BCrypt as the remaining brake.

**Known limits.**
- The per-account limit lets someone lock a known username out for 15
  minutes.
- The address is `getRemoteAddr()`. Behind the dev Compose Vite proxy every
  browser shares one address. The `lan` profile trusts Caddy's forwarded
  headers (`server.forward-headers-strategy: native`), so there it sees each
  phone. Admin password changes share the password throttle.

### 2. Ticker cache — Built

`sports.TickerCache`, in front of `TickerService`, used by `TickerController`.

**Why.** `sports.games.changed` sends `ticker.changed` to every signed-in
player, and each client then calls `GET /ticker`. That is the same query run
once per connected client, every time a live game changes. The demo's
looping games change every few seconds.

**How.** Entries live under a generation number. `GameSync` increments it
after the commit of any poll that changed a game. A reader that raced the
commit can only write under the old generation, which nobody reads again, so
a client that refetches after an invalidation always sees the new data. A
5-second TTL (`PVPICKS_TICKER_CACHE_TTL`) covers what changes with time
alone: feed freshness (the 503) and the New York date. On any Redis error it
reads PostgreSQL directly. Writes to `games` that bypass `GameSync` (only
tests do this) must call `TickerCache.invalidate()`.

### 3. One runner per scheduled job — Planned, before a second instance

Files: `sports/SportsConfiguration.java`, `sports/GamePoller.java`,
`sports/StatsPoller.java`, `bets/BetLockScheduling.java`,
`delivery/DeliveryConfiguration.java`, `demo/DemoBots.java`,
`demo/DemoGames.java`.

Every instance runs every `@Scheduled` job. Database correctness already
survives this: the relay uses `FOR UPDATE SKIP LOCKED`, and locking and
resolution take row locks. Two other things don't:

- **The provider budget.** `GamePoller` and `StatsPoller` each assume they
  are the only caller. Two instances make twice the requests against a plan
  that allows 5 a minute, and both get rate limited. Their state is also
  in memory per instance (`backoff`, `lastFetched`, `pausedUntil`, the
  active-player cursor), so the instances don't share backoff or fairness.
- **Duplicate work.** Two lock workers or two resolution workers contend for
  the same rows. Two demo bot loops double the bot activity.

**Refactor.** Wrap each job in a lease,
`SET job:<name> <instance> NX PX <interval>`, for example with ShedLock's
Redis provider. Move the pollers'
state into a Redis hash, so a different instance can pick up the next run.
Put the provider calls behind one shared token bucket (a Lua script over a
Redis key), so every instance spends one budget. Keep
`RealtimeSessions.sweep` per instance; it closes that instance's own
sockets.

**Why Redis is acceptable here.** The lease protects budget and efficiency,
not correctness. If Redis is lost, two runners may overlap and the row
locks still prevent double effects. PostgreSQL advisory locks would work
for the lease alone; Redis wins because the pollers' shared state needs a
fast, disposable home anyway.

### 4. Rate limits on public and chatty endpoints — Built

`common.RateLimiter` (fixed windows, one atomic Lua `INCR`+`PEXPIRE`) and
`common.RateLimits`, a `HandlerInterceptor` with per-route budgets per
account (per address before sign-in), plus one backstop: every write
(`POST`, `PUT`, `PATCH`, `DELETE`) counts toward 120 a minute per account,
whatever its own rule. Over a limit: 429 `rate_limited` with `Retry-After`. Like the login throttle, it fails open when Redis is
down. `pvpicks.rate-limits.enabled=false` turns it off; the shared
integration-test context does, and `RateLimitsIntegrationTest` tests the
limits directly.

| Operation | Route | Budget |
|---|---|---|
| Chat messages | `POST /tables/*/messages` | 30 a minute |
| Polls | `POST /tables/*/polls` | 5 a minute |
| Poll votes | `PUT`/`DELETE /messages/*/vote` | 30 a minute |
| Reactions | `PUT`/`DELETE /messages/*/reaction` | 60 a minute |
| Bet proposals | `POST /tables/*/bets` | 10 a minute |
| Picks | `PUT`/`DELETE /bets/*/pick` | 30 a minute |
| User search | `GET /users` | 30 a minute |
| Follows, blocks | `POST`/`DELETE /follows/*`, `/blocks/*` | 30 a minute each |
| Feedback | `POST /feedback` | 5 an hour |
| Reports | `POST /reports` | 10 an hour |
| Browser timings | `POST /perf` | 10 a minute |
| New tables | `POST /tables` | 10 an hour |
| Adding members or sending requests | `POST /tables/*/members` | 30 an hour (each call up to 100 people) |
| Member search | `GET /tables/*/members` | 60 a minute |
| WebSocket subscriptions | STOMP `SUBSCRIBE` (`delivery.SubscriptionGuard`) | 120 a minute, refused before the authorization queries |
| Every write (backstop) | `POST`/`PUT`/`PATCH`/`DELETE /**` | 120 a minute |

Write endpoints without a rule of their own are listed in
`RateLimits.EXEMPT`, each with the reason the backstop is enough (host- or
admin-only, once per item, your own setting, or a limit kept elsewhere).
`EndpointCoverageTest` fails when a write endpoint has neither a rule nor an
exemption, so a new endpoint has to choose. The chat writes your read position at most
every 3 seconds, so a busy table can't spend the backstop on its own.

Two limits stay in PostgreSQL because their rows are business records:
reports allow 20 new a day and one open report per person
(`moderation/ReportService.java`), and tomatoes have a cooldown
(`tables/TomatoService.java`). Changing the admin password shares the
sign-in throttle. In the `lan` profile Caddy
forwards the address it sees, but behind Docker Desktop on a Mac that is one
address (`192.168.65.1`) for every phone, so per-address limits are
party-wide there, which is why sign-in codes count per player
([LAN watch party](/lan#the-lan-profile)). In dev, every
browser shares the Vite proxy's address.

**Why Redis.** Limits must hold across instances. Rate-limit counters are
the canonical disposable state: losing them briefly only relaxes a limit.

### 5. Further caches — Later, when measured

Cache only shared, read-heavy data, invalidated by a post-commit generation
like the ticker's:

- **Game players and stats** (`sports/PlayerStatsLookup.java`,
  `GamePlayersController`). The same for every viewer, and changed only by
  `StatsSync`. A good second candidate.
- **Table balances** (`bets/TableBalanceController.java`). Sums over
  `ledger_entries` and pending picks for every member, on each table view.
  Cache the computed rows per table and bump the generation from the ledger
  and settlement writers. Keep the session and membership check in
  PostgreSQL on every request.

Do not cache these:

- **Debt for cap checks** (`settlement/DebtQuery.java`, used by
  `BetPickService` and the proposal services). It runs inside the
  transaction that enforces the cap. A stale value breaks the rule.
- **Chat windows and Home previews** (`chat/ChatWindowService.java`,
  `ChatPreviewService.java`). They are filtered per viewer by blocks, so hit
  rates are low and a mistake leaks a blocked user's message. The existing
  `(table_id, message_order)` index is the right tool.
- **Session validity.** See [Not recommended](#not-recommended).

## Kafka

### 6. Event-driven sports resolution — Planned

Files: `bets/SportsResolutionWorker.java`, `bets/BetLockScheduling.java`,
`sports/GameSync.java`, `sports/StatsSync.java`, `common/OutboxEvent.java`.

**Today.** Every 30 seconds, on every instance, the worker loads up to 500
pending sports bets and runs `SportsResolutionService.resolve` on each one,
whether or not its game changed. `GameSync` emits one
`sports.games.changed` per league per poll, with only a count. `StatsSync`
emits nothing. Each pending bet re-reads its game and stats about 120 times
an hour, and results that are decided early (King Of The Hill, Scorcerer)
wait up to 30 seconds.

**Refactor.**
1. Have `GameSync` and `StatsSync` emit per-game events
   (`sports.game.changed`, `sports.stats.changed`) keyed by game ID, in the
   same transaction as the snapshot.
2. Add a durable consumer group, `p2picks-sports-resolution` (one group
   shared by all instances, not per instance like realtime). It looks up
   pending bets for that game only and resolves them.
3. Keep the scan as a slow reconciliation sweep (every few minutes) for the
   time-driven case, the 20-minute grace after Final, and for missed events.

**Reliability.** Resolution already locks the bet and checks its status, so
a redelivered event is a no-op. Record `processed_events` markers only if a
consumer gains non-idempotent effects (ADR 0002). Keying by game keeps one
game's events in order on one partition, and partitions let instances
resolve different games in parallel.

**Performance.** Work becomes proportional to feed changes, not to pending
bets. Early-decided results land within seconds of the poll that decides
them.

### 7. Topic layout and partitions — Planned, before production

File: `delivery/DeliveryConfiguration.java` (`partitions(3)`).

- **Partition count.** A consumer group can't use more consumers than there
  are partitions, and Kafka can add partitions but never remove them.
  Adding them also remaps keys, so per-aggregate order breaks across the
  change. Pick the production count (for example 12) before production
  data exists.
- **One topic or several.** One topic is right while realtime is the only
  consumer. When #6 lands, the sports-resolution group would read every
  chat and table event just to skip it. Live games and dev scenarios
  produce far more sports events than any other domain. Split by domain
  then (`p2picks.sports.v1`, `p2picks.bets.v1`, `p2picks.chat.v1`, …), keyed
  by aggregate. `OutboxRelay` chooses the topic from `aggregate_type`, so the
  outbox table doesn't change.

### 8. Later Kafka uses — Later

- **Raw sports ingest stream.** `sports/FeedRecorder.java` writes every
  provider response to local files on the poller's thread, and dev chunks
  are cut from those files. Publishing raw responses to
  `p2picks.sports.raw.v1` (keyed by league and endpoint, with long retention)
  makes them replayable from any instance. It also lets parsing and storage
  (`GameSync`, `StatsSync`) become a consumer that can be re-run after a
  parser fix. Worth it once more than one instance polls, or recordings are
  needed outside a laptop.
- **Analytics and audit.** The outbox stream already holds every committed
  domain event. A separate consumer group can sink it to a warehouse or
  audit store without touching the request path. Events already carry IDs,
  not names or amounts; keep it that way (see the realtime contract).

### Realtime fan-out stays a broadcast

`RealtimeConsumer` gives each instance its own consumer group, so every
instance sees every event and serves its own sockets. That is the right
pattern for WebSocket fan-out; Redis pub/sub is not needed.

One inefficiency to fix (plain code, not Kafka): for personal and settlement
routes, the consumer runs audience queries (`RealtimeAccess.tableMembers`,
`profileAudience`, …) on every instance, even when no audience member is
connected there. Check local subscriptions first and query only for the
users present. With N instances this removes most of the N-fold duplicated
queries.

## Logs and metrics

Planned, before production. **Recommendation: don't route logs or metrics
through Kafka.** At this size, Micrometer metrics and OpenTelemetry (OTLP) to
a collector and the hosting provider's log and metrics service (for example
CloudWatch) cost less to run. They also come with dashboards and alarms.
Kafka as a log bus pays off only when many independent consumers need the
raw log stream.

What to measure, with where it comes from:

| Signal | Source |
|---|---|
| Outbox lag: age of the oldest unpublished row; relay failures | `delivery/OutboxRelay.java`, `DeliveryConfiguration.RelayScheduling` |
| Consumer lag per group | Kafka client metrics through Micrometer |
| Feed health, backoff, rate-limit pauses | `sports/GamePoller.java`, `StatsPoller.java`, `sports_feed_status` |
| Lock and resolution failures and retries | `bets/BetLockWorker.java`, `SportsResolutionWorker.java` |
| Connected sockets, closed-by-sweep count | `delivery/RealtimeSessions.java` |
| Login throttle and rate-limit rejections | `security/LoginThrottle.java`, #4 |

The admin Perf tab already shows the outbox backlog and its oldest row's age,
feed health, the database pool and per-route latency and errors, including
429s (see [Admin dashboard](/admin#perf)). `admin/AdminHealthController.java`
still reports Kafka as `NOT_CHECKED`; a broker check and consumer lag are the
remaining gaps.

## Not recommended

| Brief item | Here | Better option |
|---|---|---|
| CDC (Debezium) instead of polling | The outbox relay already publishes committed changes. No database-to-database sync exists; the sports feed is an external API, which CDC can't capture. | Keep the polling relay: 500 ms latency and one indexed query. Revisit CDC if outbox throughput reaches thousands of events a second, or commit-order delivery is needed for gap detection (on the roadmap). Note that a stalled connector holds a replication slot and grows the WAL. |
| Redis session store | Sessions are signed JWT cookies plus `auth_sessions` rows. The app is already stateless across instances (CSRF uses a cookie repository; there is no `HttpSession`). Revocation is checked in the same SQL as each read. | Keep PostgreSQL (ADR 0003). Caching validity in Redis would delay revocation by the cache's lifetime. |
| Redis locks (`SET NX`) for critical sections | Money paths lock user rows in UUID order, then table, bet, ledger and receipt rows, in PostgreSQL. | Keep the row locks: they are atomic with the writes, and a Redis lock has no fencing. Use Redis leases only for job scheduling (#3). |
| Redis sorted sets for leaderboards | The only ranking is the Peers panel sorting a table's balances in the browser (`client/src/features/tables/components/peers.tsx`), over one table's members. | `ORDER BY` or a client sort is enough. Use a ZSET, rebuilt from the ledger, only if cross-table or global rankings become a product feature. |
| Redis idempotency records | `common/IdempotencyGuard.java` keeps keys in PostgreSQL, atomically with the mutation. | ADR 0003 requires PostgreSQL as the record. A Redis copy would add nothing. |
| Kafka between modules | The modules share one process and, where needed, one transaction (ADR 0001). | Add events only where the caller doesn't need the result (#6). Revisit when a module is extracted. |
