# P2Picks Backend: Phased Knowledge Transfer Document

Deliverable: a detailed, phased knowledge transfer document saved to Files (`p2picks-backend-kt.md`, plus a `.docx` copy). The app code in this project does not change.

## Decisions it is built around
- Stack: Spring Boot 3 (Java 21), PostgreSQL, Kafka, Redis, Docker. AWS comes last.
- Shape: a modular monolith first (strict modules: identity, social, tables/chat, bets, settlement/ledger, quick-picks, notifications, admin, sports-data). It gets split into services only when the evidence calls for it, after LAN.
- Three Spring profiles: `dev`, `lan`, `prod`.
  - **dev**: mock profiles (Newbie Nate, Ivy, Pat, Admin) plus sample tables, and a mock sports feed. Used for unit, regression, end-to-end, smoke and performance testing. Minimal auth.
  - **lan**: a prod-like rehearsal for one live event of 24 hours max. Real users, real games through balldontlie. No seed data. Data is thrown away afterwards; only the audit logs are kept.
  - **prod**: on AWS, done after LAN feedback.
- Local and LAN both run with `docker-compose down -v` then `docker-compose up -d --build`. Prod is deployed through AWS.
- Credentials: a command-line script (`scripts/provision-users.sh`, calling an admin-only CLI/endpoint, kept separate from app logic) creates the LAN invite usernames and passwords. The same script loads the dev mock profiles from a YAML file, so there is one path for both.
- Sports data: one `GameFeed` interface with a single canonical event shape. `MockGameFeed` serves dev and `BallDontLieGameFeed` serves LAN, and both publish the same Kafka event schema (Avro/JSON Schema with contract tests).
- LAN logs: a bind-mounted `./audit-logs` export that survives `down -v`, plus Loki/Grafana with its own volume outside the `down -v` project.

## Document outline (phases)

**Phase 0: Foundations and contracts**
- Repo layout (`/frontend`, `/backend`, `/infra`, `/scripts`), a branching model and ADR template.
- Turn the current `useAppStore` actions into an OpenAPI spec and WebSocket topics.
- Port the domain rules (payout, wash, remainder, PASS, Quick Picks no-chaining, safety, deletion) into a list of rules to keep, with their existing tests mapped to JUnit cases.
- Exit criteria.

**Phase 1: Modular monolith skeleton (dev profile)**
- Gradle multi-module layout, Spring Modulith boundaries with ArchUnit tests.
- Flyway schema: users, follows, blocks, tables, members, invites, bets, picks, ledger (append-only, zero-sum check), settle-ups, quick-pick subscriptions, reports, feedback, audit_log.
- docker-compose for dev: app, postgres, redis, kafka (KRaft), kafka-ui, mailhog-style stubs.
- Profile files `application-dev.yml`, seeders and the provisioning script.

**Phase 2: Core betting logic**
- Bet lifecycle state machine (Active, Pending, Settled/Wash), cent-safe payouts, hidden picks until lock.
- Idempotency keys and optimistic locking. Ledger posting in one transaction with an outbox.
- Testing: unit tests, property-based zero-sum tests, Testcontainers integration tests.

**Phase 3: Events, realtime and caching**
- Kafka topics, keys, partitions, the outbox relay, consumer idempotency and DLQs.
- Quick Picks fan-out and source-result propagation over Kafka, with the host-wash override.
- Redis: presence, chat fan-out (pub/sub), table snapshots, rate limits, and the rules for clearing the cache.
- WebSocket/STOMP gateway.

**Phase 4: Sports data**
- The canonical `GameEvent` shape, the mock feed (scriptable scenarios for tests), and the balldontlie adapter with polling, rate-limit handling and backoff.
- Contract tests that make both feeds produce the same shape. The auto-lock/settle triggers.

**Phase 5: Frontend integration**
- An adapter layer in the frontend: the mock store stays as a fallback, and an HTTP/WS client is added. A runtime flag chooses between them.
- Sign-in swaps from the browser-only accounts to backend sessions.

**Phase 6: Test automation (dev)**
- Test pyramid: JUnit, Testcontainers, Playwright end-to-end tests against the mock profiles, a smoke suite run after `up -d --build`, and k6 performance tests (target users per table and latency budgets).
- CI pipeline (GitHub Actions): build, test, image build, compose smoke.

**Phase 7: LAN rehearsal profile**
- `application-lan.yml`: TLS through a Caddy/Traefik reverse proxy on the LAN IP, real auth, seeding disabled, stricter rate limits.
- Invite provisioning steps, an event-day runbook (T-24h through teardown) and a checklist for phones on the network.
- Observability: Prometheus, Grafana, Loki and a structured audit-log export. A retention script and a post-event export bundle.
- Feedback capture and a go/no-go checklist for prod.

**Phase 8: Hardening from LAN findings**
- Decide on service extraction (which modules, and why). Security review, load fixes, backups and data-deletion checks.

**Phase 9: AWS production (final)**
- ECS Fargate or EKS, RDS Postgres, ElastiCache Redis, MSK (or MSK Serverless), ALB, CloudFront + S3/edge for the frontend, Secrets Manager, Cognito or keeping JWT, and Terraform/CDK.
- Environments, the CI/CD promotion path, monitoring with CloudWatch/Grafana, cost estimates, and runbooks for disaster recovery and on-call.

**Appendices**
- Kafka topic catalog, Redis key catalog, ERD sketch, sample compose files for dev and lan, provisioning script example, profile comparison matrix, glossary.

## Open assumptions (stated in the doc; can be changed)
- Gradle, not Maven. Spring Modulith for module boundaries.
- LAN auth: username/password with a JWT session. Prod picks Cognito or keeps JWT in Phase 9.
- The Loki/Grafana stack runs as a separate compose project, so `down -v` on the app does not wipe it.

## Technical details
- Written as Markdown in `/mnt/documents/p2picks-backend-kt.md`, then converted to `.docx` (pandoc). Every page is checked as an image before delivery.
