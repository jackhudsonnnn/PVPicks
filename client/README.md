# Frontend

The current entry point (`src/features/auth/backend-app.tsx`) signs in to the
dev backend. On desktop a sidebar holds Home, Tickets, Friends, How to play and
Preferences, your tables, and your net settle-up points; phones get a top bar
and a bottom tab bar. It is the only app: every route renders it, and the
route files only supply paths and page metadata. The look follows the
original prototype (git tag `prototype-ui`), adapted to the server's data.

- **Home:** pending table invitations (join or decline), your tables,
  "Create a table" (name, icon, and people who follow you), and deleted tables
  whose chat you can still read for 90 days (read-only, at `/archives/<id>`).
- **Friends:** settle-up receipts, new followers, and lists of friends,
  following, followers and blocked people, with username search, follow,
  follow back, block and unblock. A host can only add people who follow them,
  and each person's invite preferences decide whether they join right away or
  get an invitation.
- **Table page:** a card-room felt with members seated around it (each
  showing their points at the table and, for the focused bet, their pick) and
  the open bets fanned as cards in the middle; tap the front card to open its
  ticket. A side panel (tabs on phones) holds Chat, Peers (everyone's Settle
  and True points at the table) and Host (or About for members): points
  settlement, members, roles, permissions and table settings.
- **Tickets:** every bet at your tables as tickets, by status (Active,
  Pending, Resolved, Washed) with counts, optionally for one table, paged.

- **Betting:** "+ Bet" opens the proposal sheet: pick a mode (sports modes
  show as coming soon), set up a U2Pick, the wager (+0.01/+0.10/+1.00 steps,
  0.10 to 10.00, or 4fun) and the pick window, preview the ticket, send. On a
  ticket, tap a choice to pick (you confirm first), tap it again to clear it,
  and use "Call it" on a locked ticket if its settler rule allows you.
  Creating does not place a pick. Active tickets show only your own pick and
  the pick count; picks, players and the pot appear after lock.

- **Chat:** each table has a Bets/Chat tab pair. Chat loads at your first
  unread message (marked "New"), pages older history, sends text (1–2000
  characters) and catalog GIFs, toggles one reaction per message, and moves
  your read position forward as new messages reach the bottom. Your text, GIF
  and reaction permissions apply.

- **Ticker:** a strip under the header shows today's NBA and NFL games from
  `GET /ticker`, live first, and says when live scores are unavailable rather
  than showing no games.

- **Preferences:** a Tables tab for who can add you (friends and people you
  follow, each Open, Request or Closed), an Account tab for your username
  (also your sign-in name), avatar color and debt cap, which can only change
  while you owe nothing, and a Display tab (theme, text size, motion, 24-hour
  clock) saved on this device.
- **How to play:** the game modes, bet lifecycle and payout rules, and a
  feedback form that sends to the backend.
- **Admin:** an admin account sees only a site-health view (database and
  cache status); it can't join tables, bet or chat. User audit, moderation and
  reports are not built yet.

Quick Picks, sports bets and registration are not connected. The earlier
mock-store prototype was removed from the source; it is preserved at the git
tag `prototype-ui` as a design reference.

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

For local development, run the dev backend on `127.0.0.1:8080`
configured with `PVPICKS_DEV_ORIGIN=http://127.0.0.1:5173`. From `client/` run
`npm run dev`, then visit `http://127.0.0.1:5173`. Vite proxies `/api` requests
to the backend; this development-only proxy is not a production deployment.
Sign in with an already provisioned backend account.
For a backend on another port, set `PVPICKS_DEV_BACKEND_URL` to its HTTP
loopback origin, in the shell or in a git-ignored `client/.env.local`
(`PVPICKS_DEV_BACKEND_URL=http://127.0.0.1:8090`); the shell wins.

Starting the backend applies pending migrations automatically. Do not expose
the dev backend or proxy to a network.

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
