# P2Picks Backend — Phased Knowledge Transfer Document (v3)

**Stack:** Spring Boot 3 (Java 21) · PostgreSQL 16 · Apache Kafka (KRaft) · Redis 7 · Docker / docker-compose · AWS (final phase)
**Shape:** Modular monolith first → split into services only after LAN evidence
**Profiles:** `dev` · `lan` · `prod`

---

## How to read this document

Each phase has: **Goal**, **Work items**, **Deliverables**, **Exit criteria**. Do not start a phase until the previous phase's exit criteria pass. AWS is deliberately last — the app must be proven locally (dev) and with real people watching real games (LAN) first.

### Profile matrix

| Concern | `dev` | `lan` | `prod` |
|---|---|---|---|
| Purpose | Logic, UI, perf testing against mock profiles | 24h max live rehearsal with real users & real games | Public launch after LAN feedback |
| Run command | `docker-compose down -v && docker-compose up -d --build` | same, on a LAN host | AWS CI/CD deploy |
| Seed data | Mock profiles (Newbie Nate, Intermediate Ivy, Pro Pat, Admin) + sample tables | None | None |
| Sports feed | `BallDontLieGameFeed` | `BallDontLieGameFeed` | `BallDontLieGameFeed` (paid tier only if free limits run out) |
| Auth | Minimal (provisioned users, relaxed JWT) | Provisioned invite credentials, JWT, TLS | Cognito or hardened JWT |
| Data retention | Wiped every `down -v` | Wiped after event; **audit logs kept** | Retained + backups |
| Observability | Console logs, Kafka UI | Prometheus + Grafana + Loki + `./audit-logs` export | CloudWatch + Grafana |
| TLS | No | Yes (Caddy, LAN IP / local CA) | Yes (ACM) |

---

## Phase 0 — Foundations & Contracts

**Goal:** Freeze what the backend must do before writing it. The current frontend already encodes the rules; capture them.

### Work items
1. **Repository layout**
   ```text
   p2picks/
     frontend/          # current TanStack Start app
     backend/           # Spring Boot modular monolith (Gradle)
     infra/
       compose/         # docker-compose.dev.yml, docker-compose.lan.yml, observability.yml
       aws/             # Terraform/CDK (Phase 9 only)
     scripts/           # provision-users.sh, export-audit.sh, smoke.sh
     docs/adr/          # architecture decision records
   ```
2. **Branching:** trunk-based; short-lived feature branches; `main` always green. Tags `lan-YYYYMMDD` for event builds.
3. **ADR template** (`docs/adr/0000-template.md`): Context · Decision · Consequences · Status. First ADRs: modular monolith, Kafka outbox, Redis as cache-only (never source of truth), feed abstraction.
4. **API contract:** translate every `useAppStore` action into OpenAPI 3.1 (`backend/api/openapi.yaml`):
   - Identity: `POST /auth/login`, `POST /auth/logout`, `GET /me`, `PATCH /me/profile`, `DELETE /me`
   - Social: `POST /follows/{userId}`, `DELETE /follows/{userId}`, `POST /blocks/{userId}`, `POST /reports`
   - Tables: `POST /tables`, `GET /tables/{id}`, `POST /tables/{id}/members`, `POST /tables/{id}/invites`, `POST /invites/{id}/respond`, `PATCH /tables/{id}/settings`
   - Bets: `POST /tables/{id}/bets` (propose), `PUT /bets/{id}/pick`, `DELETE /bets/{id}/pick` (No guess), `POST /bets/{id}/settle` (U2Pick settler), `POST /bets/{id}/wash`
   - Settlement: `POST /tables/{id}/settle-points`, `POST /settle-ups/{id}/confirm`, `POST /settle-ups/{id}/forgive`
   - Quick Picks: `POST /tables/{id}/quick-picks/share`, `POST .../rotate`, `DELETE .../share`, `POST .../subscribe {code}`, `DELETE .../subscribe`
   - Admin: `GET /admin/users/{id}/audit`, `POST /admin/users/{id}/actions`, `GET /admin/analytics`, `GET /admin/health`, `GET /admin/moderation`
   - Feedback: `POST /feedback`
5. **Realtime contract:** STOMP destinations `/topic/tables/{id}/chat`, `/topic/tables/{id}/bets`, `/user/queue/notifications`, `/topic/ticker`.
6. **Domain rule inventory** — each existing frontend test becomes a named JUnit case:
   - Losers' pot split evenly among winners; points are 2-decimal cents.
   - Wash if all picked the same, nobody picked the winner, or outcome outside choices.
   - No guess = participant not in pot. Clearing a pick → No guess.
   - Picks hidden while Active; revealed at lock (Pending).
   - Remainder allocation: Host / Proposer / earliest pick / deterministic random.
   - Wager 0.10–10.00, default 1.00. 4fun = nothing tracked.
   - Quick Picks: copies never chain; exact copies; source settles, subscriber host may wash own copy; copies persist after unsubscribe/rotation.
   - Blocks suppress chat + invites; reports shared to admin; 3 strikes freeze.
   - Deletion keeps ledger rows as "Deleted user", erases personal data.
   - Settle-ups: net open amount; forgive whole amount; ask once, retry after a day.
   - Ledger is zero-sum per table.

### Deliverables
OpenAPI spec, STOMP topic list, rule inventory (`docs/domain-rules.md`), 4+ ADRs.

### Exit criteria
Every frontend store action maps to an endpoint; every rule has a test name.

---

## Phase 1 — Modular Monolith Skeleton (`dev` profile)

**Goal:** A running Spring Boot app with infrastructure in Docker and empty modules enforcing boundaries.

### Work items
1. **Gradle layout** (`backend/`)
   ```text
   app/                     # Spring Boot main, wiring, profiles
   modules/
     identity/  social/  tables/  chat/  bets/  settlement/
     quickpicks/  notifications/  admin/  sportsdata/  audit/
   shared/kernel/           # Money (cents long), ids, clock, events
   ```
   Each module: `api` (public interfaces + events), `internal` (impl). Use **Spring Modulith** + **ArchUnit** tests so modules only talk through `api` or events.
2. **Money type:** store all points as `BIGINT` cents. Never `double`.
3. **Flyway migrations** (`V1__init.sql` …):
   - `users(id, username citext unique CHECK (username ~ '^[a-z0-9_]{3,15}$'), hue, password_hash, role, status, created_at, deleted_at)`
   - `follows(follower_id, followee_id, created_at)` · `blocks(blocker_id, blocked_id)`
   - `tables(id, name, host_id, invite_policy, u2pick_enabled, host_only_proposals, remainder_rule, quickpicks_code, quickpicks_sharing)`
   - `table_members(table_id, user_id, role, joined_at)` · `table_invites(id, table_id, user_id, status, created_at)`
   - `bets(id, table_id, mode, proposer_id, wager_cents, is_4fun, status, locks_at, outcome, source_bet_id, source_table_id, override_state, version)`
   - `picks(bet_id, user_id, choice, picked_at)` — No guess = no pick row
   - `ledger_entries(id, table_id, bet_id, user_id, delta_cents, created_at)` — append-only; trigger or job checks `SUM(delta)=0` per bet
   - `settle_ups(id, table_id, from_user, to_user, amount_cents, status, asked_at)`
   - `quickpick_subscriptions(subscriber_table_id, source_table_id, created_at)`
   - `chat_messages`, `reactions(message_id, user_id, emoji)` unique per (message,user)
   - `reports`, `feedback`, `admin_actions`, `audit_log(id, at, actor, action, entity, payload jsonb)`
   - `outbox(id, aggregate, type, payload jsonb, created_at, published_at)`
4. **docker-compose.dev.yml**
   ```yaml
   services:
     postgres:  { image: postgres:16, environment: { POSTGRES_DB: p2picks, POSTGRES_PASSWORD: dev }, volumes: [pgdata:/var/lib/postgresql/data] }
     redis:     { image: redis:7 }
     kafka:     { image: bitnami/kafka:3.7, environment: { KAFKA_CFG_PROCESS_ROLES: "broker,controller", ... } }
     kafka-ui:  { image: provectuslabs/kafka-ui, ports: ["8081:8080"] }
     backend:   { build: ../../backend, environment: { SPRING_PROFILES_ACTIVE: dev }, depends_on: [postgres, redis, kafka], ports: ["8080:8080"] }
     frontend:  { build: ../../frontend, environment: { VITE_API_MODE: http }, ports: ["3000:3000"] }
   volumes: { pgdata: {} }
   ```
   Run: `docker-compose -f infra/compose/docker-compose.dev.yml down -v && docker-compose -f infra/compose/docker-compose.dev.yml up -d --build`
5. **Profiles:** `application.yml` (shared), `application-dev.yml` (seeding on, balldontlie feed with a dev API key, relaxed auth, verbose SQL logs), `application-lan.yml`, `application-prod.yml`. Secrets only via env vars.
6. **Provisioning script** (`scripts/provision-users.sh`) — decoupled from app logic:
   ```bash
   # dev: load mock profiles
   ./scripts/provision-users.sh --profile dev --file scripts/dev-profiles.yaml
   # lan: create invite credentials
   ./scripts/provision-users.sh --profile lan --add alice:Pass-1234 --add bob:Pass-5678
   ./scripts/provision-users.sh --profile lan --generate 20 --out lan-invites.csv
   ```
   Calls an admin-only endpoint `POST /internal/provision` bound to localhost/docker network only (or a Spring Shell CLI `java -jar app.jar provision ...`). Dev sample tables/bets load from a separate Flyway `R__dev_seed.sql` gated to the `dev` profile (`spring.flyway.locations` per profile).

### Exit criteria
`up -d --build` brings everything healthy; `/actuator/health` green; ArchUnit boundary tests pass; mock profiles can log in.

---

## Phase 2 — Core Betting Logic

**Goal:** The heart of the app — correct, transactional, tested.

### Work items
1. **Bet state machine:** `ACTIVE → PENDING (lock) → SETTLED | WASHED`. Transitions only via domain service; illegal transitions throw.
2. **Pick rules:** pick = join; clear = No guess; edits only while ACTIVE; picks hidden in API responses while ACTIVE (return counts only).
3. **Payout engine:** pure function `settle(bet, picks, outcome, remainderRule, seed) → List<LedgerDelta>`; cent-safe; deterministic random remainder seeded by bet id.
4. **Transactions:** settlement writes `ledger_entries` + `outbox` row in **one** DB transaction. Optimistic locking via `bets.version`.
5. **Idempotency:** `Idempotency-Key` header on mutating endpoints; store key+response in Redis (24h) and a DB unique index fallback.
6. **Game modes:** port the 7 NFL/NBA modes + U2Pick with its 4 settler options; mode validation per mode.
7. **Settle Points / settle-ups / forgiveness:** zero-sum transfers; confirmation states.

### Testing
- Unit tests for every rule in the inventory.
- **Property-based tests** (jqwik): random bets/picks → ledger always sums to zero; no negative remainder.
- **Testcontainers** integration tests with real Postgres/Kafka/Redis.

### Exit criteria
All domain-rule tests pass; zero-sum property holds over 10k random cases.

---

## Phase 3 — Events, Realtime & Caching

**Goal:** Make the app live and fast without making Kafka or Redis the source of truth.

### Kafka
| Topic | Key | Producers | Consumers |
|---|---|---|---|
| `bets.lifecycle.v1` | betId | bets | notifications, quickpicks, ticker, audit |
| `bets.settled.v1` | betId | settlement | quickpicks (propagate), notifications, admin analytics |
| `chat.messages.v1` | tableId | chat | realtime gateway, audit |
| `quickpicks.fanout.v1` | sourceTableId | quickpicks | quickpicks (copy creator) |
| `games.events.v1` | gameId | sportsdata | bets (auto-lock/settle) |
| `audit.v1` | entityId | all | audit sink / log export |
| `*.dlq` | — | consumers on failure | ops |

- **Outbox relay** (polling publisher or Debezium) — never publish directly in the request.
- Consumers are **idempotent** (processed-event table keyed by event id).
- Partitions: 6 in dev/lan; ordered per key.

### Quick Picks over Kafka
- New **original** bet at a sharing table → `quickpicks.fanout.v1` → for each subscriber, create an exact copy with `source_bet_id`. Copies are never re-fanned (check `source_bet_id IS NULL`).
- `bets.settled.v1` for a source → settle pending copies with same outcome unless subscriber host already washed (`override_state=WASHED`).

### Redis
| Key | Use | TTL |
|---|---|---|
| `presence:table:{id}` | online members (set) | 60s heartbeat |
| `snapshot:table:{id}` | table view cache | 30s / evict on event |
| `ticker:home` | live ticker list | 10s |
| `rl:{user}:{route}` | rate limit (token bucket) | window |
| `idem:{key}` | idempotency responses | 24h |
| `chat` pub/sub | cross-instance chat fan-out | — |

Invalidation rule: every consumer of `bets.*`/`chat.*` evicts affected `snapshot:*`. Cache misses read Postgres.

### Realtime gateway
Spring WebSocket + STOMP; JWT on CONNECT; Redis pub/sub relay so multiple app instances broadcast. Unread divider / jump-to-latest uses server `last_read` per member.

### Exit criteria
Two browser sessions see chat and bet changes live; Quick Pick copy + settlement propagate end-to-end; killing Redis degrades speed but not correctness.

---

## Phase 4 — Sports Data

**Goal:** One canonical game shape from one real feed. `BallDontLieGameFeed` runs in **dev, lan and prod**. Points are mock dollars and the app is not built to make money, so a paid/licensed feed isn't justified. A scripted feed exists only inside automated tests.

### Canonical shape (`games.events.v1`)
```json
{
  "eventId": "uuid",
  "source": "balldontlie | test",
  "league": "NBA | NFL",
  "gameId": "string",
  "status": "SCHEDULED | LIVE | FINAL | POSTPONED",
  "startsAt": "ISO-8601",
  "period": 3, "clock": "04:12",
  "home": { "team": "BOS", "score": 88 },
  "away": { "team": "NYK", "score": 85 },
  "stats": { "players": [ { "id": "string", "name": "string", "pts": 22, "reb": 7, "ast": 5 } ] },
  "observedAt": "ISO-8601"
}
```
Publish JSON Schema in a schema registry (or versioned in repo); breaking changes → `v2` topic.

### Implementations
- `GameFeed` interface: `schedule(date)`, `poll(gameIds)`.
- `BallDontLieGameFeed` (**dev, lan, prod**): API key in env (`BALLDONTLIE_API_KEY`, one key per profile so dev testing never eats prod's quota); polling every 15–30s for LIVE games only; respect rate limits (token bucket, 429 backoff with jitter); map to canonical shape; dedupe unchanged snapshots.
- **Free-tier budget:** one poller per environment (a Redis lock so only one backend instance polls), responses cached in Redis (`games:{id}` TTL 15s, schedule TTL 10 min), and polling only for games that have open bets. Track calls/minute in Grafana and alert at 80% of the limit.
- **No live games right now (dev):** dev still uses the real schedule; to test a full game when nothing is on, use recorded balldontlie responses replayed by `ScriptedGameFeed` in tests, or run the bet as U2Pick.
- `ScriptedGameFeed` (**tests only**, never a runtime profile): recorded balldontlie responses plus scenario files (`blowout.yaml`, `overtime.yaml`, `postponed.yaml`) on a controllable clock, wired in via `@TestConfiguration`.
- **Contract tests:** the same assertion suite runs against `BallDontLieGameFeed` (recorded fixtures via WireMock) and `ScriptedGameFeed`, so the test feed can never drift from the real shape.
- Note: balldontlie coverage is strongest for NBA; confirm NFL coverage (and whether NFL needs a paid tier) during dev, otherwise run NFL bets as U2Pick.

### Triggers
Bets consume `games.events.v1`: lock at `startsAt`/mode rule; settle on `FINAL`; wash on `POSTPONED`.

### Exit criteria
In dev, a real balldontlie game locks and settles bets automatically; in CI, `ScriptedGameFeed` runs a full game end to end; contract tests green for both; call rate stays under the free limit.

---

## Phase 5 — Frontend Integration

**Goal:** Point the existing UI at the backend without rewriting screens.

1. Introduce an `AppBackend` interface mirroring `useAppStore` actions.
2. Two implementations: `MockBackend` (current in-memory store, kept for offline/tests) and `HttpBackend` (fetch + STOMP client).
3. Runtime flag `VITE_API_MODE=mock|http`; base URL `VITE_API_URL`.
4. Replace browser-only sign-in with `/auth/login`; JWT in httpOnly cookie via reverse proxy; remove localStorage account hashes.
5. Move localStorage caches (notifications, reports, feedback, blocks) to server endpoints; keep only UI prefs local.
6. Generate a typed client from OpenAPI (`openapi-typescript`).

### Exit criteria
Every screen works in `http` mode against dev compose; Playwright suite passes in both modes.

---

## Phase 6 — Test Automation (`dev`)

| Layer | Tool | Scope | When |
|---|---|---|---|
| Unit | JUnit 5, jqwik | domain rules, payout | every commit |
| Integration | Testcontainers | repos, Kafka consumers, Redis | every commit |
| Contract | JSON Schema / Spring Cloud Contract | API + feed shapes | every commit |
| E2E | Playwright | flows as Newbie/Ivy/Pat/Admin | PR + nightly |
| Smoke | `scripts/smoke.sh` | health, login, propose, pick, settle | after every `up -d --build` |
| Regression | Playwright + recorded balldontlie scenarios | past bugs (e.g. admin scrollTo crash) | nightly |
| Performance | k6 | 8-seat tables × N, chat bursts, settle storms | weekly / pre-LAN |

**Performance targets (initial):** p95 API < 200 ms, p95 chat delivery < 300 ms, 500 concurrent users on one LAN host, settlement of a 50-person bet < 1 s.

**CI (GitHub Actions):** build → unit/integration → image build → compose up → smoke → Playwright → publish images tagged with git SHA.

### Exit criteria
CI green; k6 targets met on the LAN hardware.

---

## Phase 7 — LAN Rehearsal (`lan` profile)

**Goal:** A prod-like 24h event with real people watching real games, then wipe everything except audit logs.

### Configuration (`application-lan.yml`)
- Seeding **off**; balldontlie **on** (same feed as dev and prod, its own API key).
- Real JWT auth (short access token, refresh token), BCrypt/Argon2 hashes.
- Stricter rate limits; CORS locked to the LAN hostname.
- Structured JSON logs (Logback + logstash encoder) with `traceId`, `userId`, `tableId`.

### docker-compose.lan.yml (additions over dev)
- `caddy` reverse proxy: TLS with local CA (`tls internal`) at e.g. `https://p2picks.lan`; phones must trust the CA or use a real domain + DNS challenge.
- No published DB/Redis/Kafka ports.
- `audit-exporter` writes `audit.v1` + app logs to **bind mount** `./audit-logs/` (survives `down -v`).
- Observability stack in a **separate compose project** (`observability.yml`: Prometheus, Grafana, Loki, Promtail) with its own named volumes so `down -v` on the app doesn't wipe it.

### Event-day runbook
| When | Action |
|---|---|
| T-7d | Confirm balldontlie coverage for chosen games; k6 run on LAN host |
| T-1d | `down -v && up -d --build` with `lan-YYYYMMDD` tag; run smoke; generate invites: `provision-users.sh --profile lan --generate 30 --out invites.csv` |
| T-2h | Hand out credentials; phones join Wi-Fi, trust CA, sign in; create tables |
| Live | Watch Grafana (latency, errors, Kafka lag, feed freshness); admin handles reports |
| T+end | Collect in-app feedback; `scripts/export-audit.sh` → `audit-YYYYMMDD.tar.gz` |
| ≤24h | `docker-compose down -v` (data gone; audit logs + observability kept) |

### Exit criteria / go-no-go for prod
No ledger zero-sum violations; zero data-loss incidents; p95 within targets; feedback triaged; list of service-split candidates.

---

## Phase 8 — Hardening from LAN Findings

1. **Service extraction decision** (ADR): split only modules with clear pressure — likely candidates: `chat/realtime` (connection load), `sportsdata` (external I/O), `notifications`. Keep bets+settlement+ledger together (shared transaction).
2. Security review: OWASP ASVS L2 checklist, dependency scan, rate limits, admin endpoint isolation, secrets rotation.
3. Data: backup/restore drill, deletion flow ("Deleted user") verified against real data, retention policy.
4. Fix all LAN bugs with regression tests.
5. Legal/social-gaming review of copy (points are mock dollars; no fees/rake).

### Exit criteria
All P0/P1 LAN issues closed; prod infra plan approved.

---

## Phase 9 — AWS Production (final)

### Target architecture
```text
Users → CloudFront → (S3 static / frontend container)
                   → ALB → ECS Fargate (backend services, autoscaling)
                              ├─ RDS PostgreSQL (Multi-AZ)
                              ├─ ElastiCache Redis
                              └─ Amazon MSK (or MSK Serverless)
Secrets Manager · CloudWatch · X-Ray/OTel · WAF · ACM · Route 53
```

### Work items
1. **IaC:** Terraform (or CDK) modules for VPC (private subnets), RDS, ElastiCache, MSK, ECS, ALB, CloudFront, WAF.
2. **Environments:** `staging` (prod profile, small) → `prod`. Same images promoted by SHA.
3. **CI/CD:** GitHub Actions → ECR → ECS blue/green (CodeDeploy) with smoke gate.
4. **Auth:** decide Cognito vs keeping app JWT (ADR). Migrate LAN learnings.
5. **Observability:** CloudWatch logs/metrics, alarms (5xx, p95, Kafka lag, DB CPU), Grafana optional.
6. **Data:** RDS automated backups + PITR; Flyway runs as a one-off ECS task before deploy.
7. **Cost estimate (small launch, rough):** RDS t4g.small Multi-AZ ~$60, ElastiCache t4g.micro ~$15, MSK Serverless ~$100+, Fargate 2 tasks ~$40, ALB ~$20, CloudFront low → **~$250–350/mo**. Consider self-managed Kafka-on-ECS or Redpanda if MSK cost is too high.
8. **Runbooks:** incident response, DR (RPO 5 min / RTO 1 h), on-call rotation, key rotation.

### Exit criteria
Staging soak 1 week; load test passes; DR drill succeeds; launch.

---

## Appendices

### A. Module dependency rules
`bets` → `tables` (api), `sportsdata` (events) · `settlement` → `bets` (api) · `quickpicks` → `bets` (api/events) · `notifications`, `audit`, `admin` → events only.

### B. Dev mock profiles (`scripts/dev-profiles.yaml`)
```yaml
- { username: newbie,       password: newbie123, first: Newbie,       last: Nate, role: PLAYER }
- { username: intermediate, password: inter123,  first: Intermediate, last: Ivy,  role: PLAYER }
- { username: pro,          password: pro123,    first: Pro,          last: Pat,  role: PLAYER }
- { username: admin,        password: admin123,  first: Admin,        last: "",   role: ADMIN }
```

### C. Smoke script outline (`scripts/smoke.sh`)
1. Wait for `/actuator/health` = UP. 2. Log in as provisioned user. 3. Create table. 4. Propose bet. 5. Pick. 6. Settle a U2Pick bet (doesn't depend on a live game being on). 7. Assert ledger sum = 0. Exit non-zero on failure.

### D. Glossary
**Wash** — bet voided, no points move. **No guess** — participant not in pot; a sports "Pass" remains a valid choice. **U2Pick** — custom bet settled by chosen settler. **Quick Picks** — one-way subscription copying another table's original proposals. **Outbox** — DB table guaranteeing events are published after commit.

### E. Open assumptions (change via ADR)
Gradle over Maven · Spring Modulith · JWT for LAN · observability as separate compose project · balldontlie in every profile (scripted feed in tests only) · balldontlie NFL coverage to be verified.

---

## v3 Addendum — Frontend Contract Changes Since v2 (authoritative)

Where this section conflicts with earlier phases, **this section wins**. Mirror the pure TS modules named below 1:1 in Spring services and port their tests.

### 1. Identity (username-only)
- No first/last names anywhere. `username` 3–15 chars, `^[a-z0-9_]+$`, case-insensitive unique (`citext`). Display = bare username; avatar = first 2 letters uppercased + `hue`.
- Deleted users: ledger rows kept, shown as "Deleted user"; personal data erased; hosted tables handed off (`settings/deletion.ts`).

### 2. Balances (`table/scenarios.ts`) — exactly two
- **True** = resolved unsettled deltas − open pending wagers. Stored value.
- **Settle** = resolved unsettled deltas only; zero-sum per table; host "Settle points" zeroes each member by their Settle balance and creates settle-up lines.
- "Winner" balance removed. Cache table sums keyed by `(last_settlement_id, bets_version)`.

### 3. Bet lifecycle
- Every game bet resolves at **end of game** (no "Resolves at"). Stat/score modes carry `includes_overtime boolean default true`; Choose Their Fate and U2Pick don't.
- On create, store `baseline jsonb` (initial scores/stats, possessing team, yard line, spread). Details "Info" recomputes current/net from the live feed on each open.
- Game bets settle **only from the feed** (`gameResult`); people only settle U2Pick.
- Picks hidden while Active; revealed at lock (Pending). Pick stats always shown after lock.
- `remainder_rule` snapshotted onto the bet at lock; host cannot change the table rule while any Active bet exists (reject with 409 → "bet failed" modal).
- Tables may have multiple hosts. For the Host remainder rule, select one current host uniformly at random at each bet's lock and persist that recipient with the rule in the lock transaction. The committed choice remains fixed after host removal or account deletion; retries reuse it. A missing host is an invariant failure, not a reason to redirect the remainder to a winner. This recipient is distinct from the host who initiates table points settlement.
- A host can delete a table only with zero Active/Pending bets and after all
  resolved, non-4fun bet points have been settled. A zero-net group of resolved
  bets can be settled without creating payment receipts. Existing unpaid
  receipts remain accessible and actionable through personal settlement
  history. Deletion removes the table from normal active-table views but keeps
  a read-only chat archive for members at deletion, subject to blocks and
  account-deletion rules; it does not grant access to former members. Existing
  Quick Pick copies survive. This user refinement supersedes the earlier
  Active/Pending-only deletion rule; implementation and retention policy remain
  to be designed.

### 4. Debt cap (`settings/debt-cap.ts`)
```sql
CREATE TABLE debt_caps (user_id uuid PRIMARY KEY REFERENCES users, cap_cents int NULL, updated_at timestamptz);
```
- Off (`NULL`) by default. `user_debt = Σ negative table True balances + Σ negative open settle-up nets` (no offsetting).
- Change/disable allowed **only when user_debt = 0**.
- Enforced on propose, pick, and at lock: over-cap picks flip to No guess + system chat line. 4fun bets exempt.

### 5. Settle-up receipts (`social/settle-receipts.ts`, `settle-groups.ts`, `settle-changes.ts`)
```sql
CREATE TABLE settlement_lines (
  id uuid PRIMARY KEY, settlement_id uuid NOT NULL, table_id uuid NOT NULL,
  payer_id uuid NOT NULL, payee_id uuid NOT NULL, amount_cents int NOT NULL CHECK (amount_cents > 0),
  status text NOT NULL CHECK (status IN ('unpaid','sent','paid','disputed','forgive_asked','forgiven')),
  revision int NOT NULL DEFAULT 0, created_at timestamptz, updated_at timestamptz);
```
- Each host settlement creates immutable lines; repeated settlements of the same table are separate rows.
- Flow: payer `sent` → payee `paid` (confirm) or `disputed` (back to open). Payee may forgive whole amount; payer may ask once, retry after 24h.
- `sent` stays outstanding in net until confirmed; only `paid`/`forgiven` close.
- `settlePerson(other)` batch-updates all open lines with one person atomically.
- Revision guard: if a pair's net changes while a line is `sent`/`forgive_asked`, reset to `unpaid` and notify "review new amount".
- Friends shows one card per person (net, green/red); receipts popup = Table | Sent | Received.
- Chat "Points settled" message is static (who owes who), never live.

### 6. Profile & history
- Profile modal tabs: Bets / Settlements (read-only, links to receipts); table filter defaults to current table, else All. Tomato throw only with a specific table.
- Endpoints: `GET /users/{id}/history?tableId=&cursor=`, `GET /users/{id}/settlements?tableId=`.

### 7. Quick Picks
- Always-on 6-char code per table; exact copies auto-posted; source U2Pick settler keeps authority; subscriber host may wash own copy; copies never chain. Free now.

### 8. Safety, social, notifications
- Mutual follow = friends; invite policy Open/Request/Closed per friends & following. Block = mute + no invites/follows. Reports and guide feedback feed the admin queue.
- Notifications: inbox only (no toasts); prefs filter by category; cursor-paged history with cap. Advancing a table's chat read position marks that user's chat notifications through that position read across sessions; late-arriving notifications for already-read messages start read.
- Admin audits must not disclose other users' Active participation, picks or user-specific participation-derived counts; those may be revealed at lock. An admin using a member endpoint has the same own-pick and aggregate-count visibility as other members.

### 9. New/changed endpoints (summary)
| Method | Path | Notes |
|---|---|---|
| PUT | /me/debt-cap | 409 if debt > 0 |
| GET | /me/debt | live user_debt |
| POST | /settlement-lines/{id}/sent \| confirm \| dispute \| forgive \| ask-forgive | revision in body |
| POST | /people/{id}/settle | batch, `step` + expected revision |
| GET | /people/settle-groups?includeHistory= | Friends cards |
| PATCH | /tables/{id}/remainder | 409 if active bet |
| DELETE | /tables/{id} | 409 if active/pending |
| GET | /chat/{tableId}/messages?before=&after=&around= | windowed paging (30 initial, 25/page) |

### 10. Kafka events added
`bets.locked.v1` (incl. remainder snapshot, passed-by-cap list), `debtcap.enforced.v1`, `settlement.line.updated.v1` (status, revision), `settlement.revision.reset.v1`, `quickpick.copied.v1`.

### 11. Edge-case cheat sheet
- Leftover cents assigned at lock per snapshotted rule; all money in integer cents.
- Wash: all same pick, nobody picked winner, or outcome outside choices; No guess is not in pot.
- Balance change mid settle-up → reset + notify (never silently keep stale amount).
- Debt cap checks use True balance including pending wagers.

### 12. Ticker refinement
- The user accepted the recommendation to match the prototype's sports-only
  ticker. `GET /ticker` supplies a game snapshot, and `/topic/ticker` carries
  generic game-snapshot invalidations. Do not put bets, picks, participants,
  table identities or financial data in the shared ticker.
- Include games scheduled for the current `America/New_York` calendar date
  and live games from earlier dates, so an overnight game does not disappear.
  Keep presentation repetition and animation in the client. A separate bet
  activity feed is deferred; the earlier `bets.lifecycle.v1` ticker consumer
  is not part of this sports-only contract.
