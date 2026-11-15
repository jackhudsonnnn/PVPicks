# Frontend

The app (`src/app/app.tsx`) signs in to the dev backend. On desktop a sidebar holds Home, Tickets, Friends, How to play and
Preferences, your tables, and your net settle-up points; phones get a top bar
and a bottom tab bar. It is the only app: every route renders it, and the
route files only supply paths and page metadata. The design came from an
earlier prototype (git tag `prototype-ui`).

- **Signing in:** the sign-in page shows the demo account picker when the
  backend has the demo, and the password form otherwise. While the backend is
  unreachable (a restart, say), it keeps checking every 3 seconds and switches
  to the picker when it answers. Every sign-in starts on Home, whatever URL the
  last session left open, so a different account never lands on someone
  else's page. Signing out, an expired session and a demo reset all return to
  sign-in.

- **Home:** a greeting ("Sunday slate / Hey, name") and one sideways-scrolling
  row of cards that snaps card by card.
  - Invitations come first, in yellow: "theo_r wants you at this table.
    Accept?" opens a dialog to accept or decline.
  - Then your tables, each with its icon, a Host badge if you host, the
    latest chat message (live), and member avatars. A table where you have a
    pick in an open bet shows green ("in play", from
    `GET /me/bets?status=ACTIVE`).
  - On desktop the cards stack as rows in a scrolling box, and a Tickets
    column sits beside them in a scrolling box of the same height. Both
    boxes have transparent backgrounds. It lists every bet at your tables: live first,
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
  settlement, members, roles, permissions and table settings. The page loads
  only the table's live bets; older ones load when opened. Tapping a person
  shows their record at the table (computed by the server) and their bets
  there, newest first, a page at a time.
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
  characters) and GIFs from the server's library, toggles one reaction per
  message, and moves your read position forward as new messages reach the
  bottom. Your text, GIF and reaction permissions apply. Bet cards show each
  bet's question, mode and current status, also in deleted tables' archives.

- **Dev and docs icons** (dev backend only): beside the logo, in the app and
  on the sign-in page. The document icon opens these docs in a new tab, on the
  app's own address (the dev server proxies `/docs/` to the backend). The
  flask opens the Dev page, which also works signed out (`/dev` without a
  session shows it with a "Sign in" button).
- **Dev page** (dev backend only, flask icon beside the logo): the demo
  world's size and what its bots have done, a Reset (the app shows a
  "Resetting" screen, then the account picker), and the scenario files and
  recorded chunks that add DEV games, marked DEV in the ticker and game
  picker. See [Demo world](/demo-data) and [Dev sports data](/dev-sports-data).

- **Permissions come from the server.** Which bets you may call or override,
  which receipt actions you may take, and which modes can be proposed are all
  sent by the backend; the client only renders them.

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
- **Admin:** an admin account sees only admin tools; it can't join tables,
  bet or chat.
  - **Moderation:** open reports, with the reported message, to dismiss or
    strike (the third strike freezes the account and signs it out), plus
    players with strikes or frozen accounts, to unfreeze or clear strikes.
  - **Site health:** database and cache status.
- **Account deletion:** Preferences → Account → "Delete my account", with
  your password (`demo` for demo accounts). It's refused, with the reason, until you're square (no open
  bets, unsettled table points or open receipts). A frozen account sees
  "This account is frozen" at sign-in.

Quick Pick subscriptions, the player-stat bet modes and registration are not
connected.

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
actions. The retry box appears only once an outcome is unknown, never while
an action is still being sent, so the page doesn't jump on every action.

What you're told about an action is a toast, not text on the page. Errors show
as toasts; successes are silent (the screen updating is the confirmation)
unless there's something worth saying, like "Unblocked. Follows are not
restored." Loading never shows text either: lists, pages, chat, the ticker and
names show grey placeholders shaped like what's coming
(`components/loading.tsx`), and busy buttons show a spinner. The empty state
("Nothing here yet") only appears once a list is known to be empty.

## Source layout

Code is organized by feature. `client/AGENTS.md` has the detailed rules for what lives where.

- `src/app/`: the frame. Session, sign-in routing, the shell, and the realtime
  connection's lifetime.
- `src/features/<name>/`: one folder per product area:
  - `auth`: session, sign-in and the demo account picker;
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
- `src/routes/`: path stubs for TanStack Router's file-based routes (`__root.tsx` is the shell; `routeTree.gen.ts` is generated, don't edit it). They render nothing.
- `src/test/`: test setup and the isolated-backend helper for the integration
  tests.

`npm run lint` enforces these boundaries. Tests may import any module, so they
can mock exactly what they need.

## Running locally

`docker compose up -d --build` from the project root runs the client's Vite
dev server in the `client` container at `http://127.0.0.1:5173`
(`PVPICKS_CLIENT_PORT`), with `client/` mounted, so edits hot-reload. It
proxies `/api`, including the realtime WebSocket, to the backend over the
Compose network. After changing dependencies, rebuild it with
`docker compose up -d --build client`.

To run the dev server on your machine instead, stop the container
(`docker compose stop client`), then run `npm run dev` from `client/`. It
proxies to `http://127.0.0.1:8080`. For a backend on another port, set
`PVPICKS_DEV_BACKEND_URL` to its loopback origin, in the shell or in a
git-ignored `client/.env.local` (`PVPICKS_DEV_BACKEND_URL=http://127.0.0.1:8090`).

Pick an account on the sign-in page; there are no dev passwords (see
[Demo world](/demo-data)). Every demo account's password is `demo`, for the
password form and account deletion.

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
