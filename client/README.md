# Frontend

The app (`src/app/app.tsx`) signs in to the dev backend. On desktop a sidebar holds Home, Tickets, Friends, How to play and
Preferences, your tables, and your net settle-up points; phones get a top bar
and a bottom tab bar. It is the only app: every route renders it, and the
route files only supply paths and page metadata. The look follows the
original prototype (git tag `prototype-ui`), adapted to the server's data.

- **Home:** a greeting ("Sunday slate / Hey, name") and one sideways-scrolling
  row of cards that snaps card by card.
  - Invitations come first, in yellow: "theo_r wants you at this table.
    Accept?" opens a dialog to accept or decline.
  - Then your tables, each with its icon, a Host badge if you host, the
    latest chat message (live), and member avatars. A table where you have a
    pick in an open bet shows green ("in play", from
    `GET /me/bets?status=ACTIVE`).
  - On desktop the cards stack as rows in a scrolling box, and a Tickets
    column sits beside them. It lists every bet at your tables: live first,
    then pending, resolved and washed, newest first within each. It loads
    more as you scroll, and "All tickets" opens the Tickets page. Phones use
    the Tickets page instead.
  - "New table" opens a dialog: a name of up to 14 characters, an icon, and
    people who follow you.
  - Below the row are deleted tables whose chat you can still read for 90
    days (read-only, at `/archives/<id>`).
- **Friends:** settle-ups first, as a sideways-scrolling row with one card
  per person.
  - A card shows your open net with that person: red when you pay them, green
    when they pay you. It also says how many tables and open receipts that
    covers, and whether a payment awaits confirmation.
  - "Settle-up receipts" opens a dialog with any balance-change warning to
    review, group actions (mark everything sent or received, forgive or ask
    to forgive), and each dated receipt. A receipt shows its table, the
    amount, and who has sent and who has received, with your actions on it.
- Friends also lists new followers, and lists of friends,
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
  Pending, Resolved, Washed; it opens on Active), optionally for one table (a
  dropdown), paged. A ticket says which table it's from and links to it; its
  player avatars open that table's Peers tab, and Details links to the table.

- **Betting:** "+ Bet" opens the proposal sheet: pick a mode (U2Pick, Total
  Disaster or Give And Take; the player and play-by-play modes show as coming
  soon), set up a U2Pick or pick one of today's games and set the line and
  whether overtime counts, the wager (+0.01/+0.10/+1.00 steps,
  0.10 to 10.00, or 4fun) and the pick window, preview the ticket, send. On a
  ticket, tap a choice to pick (you confirm first), tap it again to clear it,
  and use "Call it" on a locked ticket if its settler rule allows you. Sports
  tickets show the game's score and settle from the feed; hosts can override
  them (wash or set the result) until the table's points are settled.
  Creating does not place a pick. Active tickets show only your own pick and
  the pick count; picks, players and the pot appear after lock.

- **Chat:** each table has a Bets/Chat tab pair. Chat loads at your first
  unread message (marked "New"), pages older history, sends text (1–2000
  characters) and catalog GIFs, toggles one reaction per message, and moves
  your read position forward as new messages reach the bottom. Your text, GIF
  and reaction permissions apply.

- **Ticker:** a strip under the header (games sized to their content on phones) shows today's NBA and NFL games from
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

Quick Pick subscriptions, the player-stat bet modes and registration are not
connected. The earlier
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

## Source layout

Code is organized by feature. See `AGENTS.md` for what lives where.

- `src/app/`: the frame. Session, sign-in routing, the shell, and the realtime
  connection's lifetime.
- `src/features/<name>/`: one folder per product area:
  - `auth`: session and sign-in;
  - `people`: names, avatars and colors;
  - `tables`: home, the table page, members and host settings;
  - `bets`: tickets, the proposal sheet, the Tickets page and bet rules;
  - `chat`;
  - `social`: friends, profiles, reports and blocks;
  - `settlements`: receipts;
  - `sports`: the score strip;
  - `preferences`: including display settings;
  - `guide`: How to play and feedback;
  - `admin`.

  Files are flat, named for what they hold (`api.ts`, `actions.ts`, `rules.ts`,
  `*-model.ts`, one file per screen or panel), with their tests beside them.
  A `components/` subfolder is used only once a feature passes about 15 files.
  Other code reaches a feature only through its `index.ts`.
- `src/components/` and `src/lib/`: shared UI and infrastructure. This covers
  the HTTP client, retryable mutations, query keys, the realtime client and
  storage. They never import features.
- `src/routes/`: path stubs for TanStack Router. They render nothing.
- `src/test/`: test setup and the isolated-backend helper for the integration
  tests.

`npm run lint` enforces these boundaries. Tests may import any module, so they
can mock exactly what they need.

## Running locally

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

Run `npm test`, `npm run lint` and `npx tsc --noEmit` for client checks. npm is
the package manager (`package-lock.json` is the only lockfile).
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
`features/bets/betting-integration.test.tsx` twice: once to create and pick, then, after
the backend locks the bet, to call the result and settle points. They do not
exercise a browser.
