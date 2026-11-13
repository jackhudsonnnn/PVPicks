# Frontend

The current entry point signs in to the dev backend and shows read-only table
and bet lists and details. Active bets show only the viewer's participation
and aggregate pick count; picks and participant names are revealed after lock.
Unavailable profiles are reported with a retry option. The mock-backed
app remains in the source but is not exposed. Betting actions, chat,
registration and other frontend workflows are not connected.

A settlement receipt and durable-review preview is wired only for tests via
the `BackendApp` test prop; the normal entry point does not enable it. It reads
private warnings, supports individual and group receipt actions, and preserves
64-bit cent values. An uncertain action is saved per user in the current tab's
session storage; retry uses the same key and is refused after 23 hours. Before
clearing it, check receipt history because the action may already have succeeded.
Storage failure disables actions. These safeguards are not cross-tab or
cross-device recovery, and the backend receipt adapters remain gated.

A separate test-only `bettingPreview` enables U2Pick creation, picking and
clearing against gated backend adapters. Creating does not place a pick;
clearing a joined pick leaves the player as No guess. Active picks remain
private. It uses the same per-user, current-tab retry safeguards as settlement;
check the original table and your pick before clearing an uncertain action.
Neither preview is available from the normal entry point.

For local development, use an approved isolated backend on `127.0.0.1:8080`
configured with `PVPICKS_DEV_ORIGIN=http://127.0.0.1:5173`. From `client/` run
`npm run dev`, then visit `http://127.0.0.1:5173`. Vite proxies `/api` requests
to the backend; this development-only proxy is not a production deployment.
Sign in with an already provisioned backend account.
For an isolated backend on another port, set `PVPICKS_DEV_BACKEND_URL` to its
HTTP loopback origin when starting Vite.

Starting the backend applies pending migrations automatically. Do not start it
against shared infrastructure without approval, and do not expose the dev
backend or proxy to a network.

Run `npm test` and `npx tsc --noEmit` for client checks.
The lint toolchain temporarily overrides its transitive minimatch dependency
to a patched major version; recheck the override when upgrading typescript-eslint.
The opt-in frontend/backend integration test requires installed client
dependencies, JDK 21, and Docker. From `backend/`, run:

```sh
PVPICKS_FRONTEND_INTEGRATION=true ./gradlew :app:test --rerun \
  --tests 'com.p2picks.app.FrontendPreviewIntegrationTest'
```

It uses Testcontainers and a random loopback port, registers gated controllers
only in the test context, and runs the React preview through real HTTP with a
test cookie jar. It does not exercise a browser or enable the normal app gate.
