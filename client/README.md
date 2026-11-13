# Frontend

The current entry point (`src/features/auth/backend-app.tsx`) signs in to the
dev backend and connects tables, bets and settle-up receipts:

- **U2Pick betting:** create a bet, pick or clear a choice, call the result or
  wash it once the bet locks (if the bet's settler rule allows you), and, as a
  host, settle the table's resolved points. Creating does not place a pick;
  clearing a joined pick leaves the player as No guess. Active bets show only
  the viewer's participation and the aggregate pick count; picks and
  participant names are revealed after lock.
- **Receipts:** private balance-change reviews, individual and group receipt
  actions, and receipt history, preserving 64-bit cent values.

- **Chat:** each table has a Bets/Chat tab pair. Chat loads at your first
  unread message (marked "New"), pages older history, sends text (1–2000
  characters) and catalog GIFs, toggles one reaction per message, and moves
  your read position forward as new messages reach the bottom. Your text, GIF
  and reaction permissions apply.

Table management, registration and the other prototype workflows are not
connected; the mock-backed app remains in the source but is not exposed.

Bets and receipts update live. While signed in, the app keeps one STOMP
connection to the backend's `/ws` (through `/api/ws`), sends the CSRF token on
CONNECT, and refreshes a table's bets or your receipts when the server says
they changed. It refetches everything after each (re)connect, reconnects with
backoff, drops duplicate invalidations, and checks the session when the socket
closes. The header shows "Live" or "Reconnecting…". Messages never carry
picks or amounts; the views always reload from HTTP.

Every mutation sends an `Idempotency-Key`. When an outcome is unknown (for
example, a lost response), the action and its key are saved per user in
local storage. Every tab sees it and blocks new bet or receipt actions until
it is retried with the same key or cleared after review; retries are refused
after 23 hours. Before clearing, check the bet list, your pick or receipt
history, because the action may have succeeded. Other devices do not see the
saved action; they reconcile from the server's reads. Storage failure disables
actions.

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
The opt-in frontend/backend integration tests require installed client
dependencies, JDK 21, and Docker. From `backend/`, run:

```sh
PVPICKS_FRONTEND_INTEGRATION=true ./gradlew :app:test --rerun \
  --tests '*FrontendIntegrationTest'
```

They use Testcontainers and a random loopback port, and run the React app
through real HTTP with a test cookie jar. The betting test runs
`betting-integration.test.tsx` twice: once to create and pick, then, after
the backend locks the bet, to call the result and settle points. They do not
exercise a browser.
