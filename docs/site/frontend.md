# Frontend

The app (`src/app/app.tsx`) signs in to the dev backend. From 1100px wide (the `nav` breakpoint) a sidebar holds Home, Tickets, Friends, How to play and
Preferences, your tables and table requests (below), and your net settle-up points; narrower screens get a top bar
and a bottom tab bar. It is the only app: every route renders it, and the
route files only supply paths and page metadata. The design came from an
earlier prototype (git tag `prototype-ui`).

- **Sidebar tables and requests** (`tables/components/sidebar-tables.tsx`):
  - On Home, which already shows your tables, the sidebar lists only
    **Requests**, newest first, and nothing at all when none are waiting.
  - On other pages it lists **My tables**, by most recent bet. While
    requests are waiting, a My tables / Requests switch shows either.
  - Table and request rows are the same height (a request shows the table
    and who sent it on one line), and only the list scrolls, so switching
    lists never moves the header or the points box.
  - Long lists mount 20 rows at a time and add more as you scroll.
  - A request opens the same accept-or-decline dialog as Home's cards, and
    accepting opens the table.
  - Requests exist only while someone's invite preference for you is
    Request; Open adds you directly and Closed refuses.
  - The order follows the last fetch of the list (opening a page, joining or
    leaving a table, a reconnect), not each new bet, so rows don't jump
    around while you look at them.
  - Answers use their own retry key (`useInviteActions`), separate from
    follows, blocks and reports.

- **Signing in:** the sign-in page shows the demo account picker when the
  backend has the demo, and the password form otherwise. While the backend is
  unreachable (a restart, say), it keeps checking every 3 seconds and switches
  to the picker when it answers. Every sign-in starts on Home, whatever URL the
  last session left open, so a different account never lands on someone
  else's page. Signing out, an expired session and a demo reset all return to
  sign-in.

- **Home:** a greeting ("Sunday slate / Hey, name"), then **Tables**
  (`tables/components/table-cards.tsx`).
  - Below 1024px the cards are one sideways row that snaps the nearest card
    to the center. It opens centered on your most recent table.
  - Without the sidebar (below 1100px), table requests (invitations that
    need your answer) sit to the left of your tables, in yellow: the newest
    right next to them, older ones further left. "theo_r wants you at this
    table. Accept?" opens a dialog to accept or decline, and accepting opens
    the table. Answering or loading requests on the left keeps the card
    you're looking at in place. With the sidebar, requests are there instead,
    not on Home.
  - Your tables go by most recent bet (or when you joined, if that's later).
    Each card has its icon, a Host badge if you host, the latest chat message
    (live), and avatars for the first members plus "+N" for the rest. A table
    where you have a pick in an open bet shows green ("in play", from
    `GET /me/bets?status=ACTIVE`).
  - Both lists mount 20 cards at a time and add more as you scroll toward
    their ends (up to 100 of each; see the membership limits in the
    [domain rules](/reference/domain-rules#membership-limits)).
  - On desktop the cards stack as rows in a scrolling box, and a Tickets
    column sits beside them in a scrolling box of the same height. Both
    boxes have transparent backgrounds. It lists every bet at your tables: live first,
    then pending, resolved and washed, newest first within each. It loads
    more as you scroll, and "All tickets" opens the Tickets page. Below
    1024px, screens at least 790px tall show **Tickets** below the table
    row, as part of the page rather than a box of its own (the page
    scrolls). Shorter screens use the Tickets page instead
    (`HOME_TICKETS` in `src/lib/use-media-query.ts`).
  - "New table" opens a dialog: a name of up to 14 characters, an icon, and
    people who follow you.
  - Below the row are deleted tables whose chat you can still read for 90
    days (read-only, at `/archives/<id>`).
- **Friends:** settle-ups first, as a sideways-scrolling row with one card
  per person you have open receipts with (or a send still in its reject
  window).
  - A card shows your open net with that person ("You pay them" / "They pay
    you" / "You're square") and how many tables and open receipts that
    covers. It's **yellow** when something needs you (a balance change to
    review, or a send to you that you can still reject; the color says it,
    with no extra text on the card), otherwise **red** when you owe,
    **green** when you're owed and **neutral** when even. Cards sort
    yellow, red, green, neutral (`settlements/ledger-model.ts`).
  - **Ledger** opens the dialog (`settlements/ledger-dialog.tsx`), headed
    "You owe X to name", "You're owed X from name" or "You are even with
    name", with two tabs:
    - **Settle:** any balance change to review ("Got it"), then the open
      receipts with checkboxes and Select all. A large, bold line says what
      the selection comes to ("You pay X", "name pays you X", "These even
      out", or "Choose receipts to settle."). The selection's net decides
      the buttons: **Send X** when you pay it, **Mark X received** (or
      **Settle even**) when you're paid, and **Forgive** when every receipt
      is owed to you. Each asks to confirm.
    - **History:** every settle, newest first, a page at a time: who sent,
      received or forgave how much, over how many receipts, and whether it
      was rejected or can still be. The receiver of a send gets **Reject**
      for 48 hours. It opens on History when you have one to reject.
- Below settle-ups, **My network** (same heading style), your people in tabs
  (`social/friends.tsx`), each newest first: **Friends** (mutual follows, by when the second follow happened),
  **Requests** (people who follow you that you don't follow back: follow
  them to be friends), **Following**, **Blocked** and **Find** (username
  search). Followers are friends plus requests, so there's no separate
  followers list. A line under the tabs, in the same style as the
  settle-ups description, explains each one. Every list, search results
  included, is a sideways row of cards the size of the settle-up cards,
  mounting 20 at a time as you scroll. A card shows the person and what they
  are to you, with full-width Follow or Unfollow and Block or Unblock
  buttons, each asking to confirm. A host can only add people who follow them, and each person's
  invite preferences decide whether they join right away or get an
  invitation.
- **Table page:** a card-room felt with members seated around it (each
  showing their points at the table and, for the focused bet, their pick) and
  the open bets fanned as cards in the middle; tap the front card to open its
  ticket. A side panel beside the felt from 1455px wide (the `split`
  breakpoint; tabs below that, where both get crowded) holds Chat, Peers (everyone's Settle
  and True points at the table) and Host (or About for members): points
  settlement, members, roles, permissions and table settings. A table can
  have up to 500 people, so Peers, the members list and the followers to add
  mount 20 rows at a time as you scroll. A Peers search asks the server for
  members whose username matches (`GET /tables/{id}/members?q=`), 50 at a
  time, so only those rows load. The page loads only the table's live bets; older
  ones load when opened. Tapping a person
  shows their record at the table (computed by the server) and their bets
  there, newest first, a page at a time.
- **Tickets:** every bet at your tables as tickets, by status (Active,
  Pending, Resolved, Washed; it opens on Active), optionally for one table (a
  dropdown under the status tabs: full width on phones, at the right on wider
  screens), paged. Tickets, Friends and How to play have no visible page
  title (each keeps a screen-reader heading). Under the question a ticket shows "Proposed by" with the
  proposer's avatar and name (opening their profile at that table,
  `?profile=<id>`); the time is in Details. The footer shows the table's emoji
  and name (linking to it) beside the wager and picks or pot, on every
  ticket.
- **Ticket Details** (`bets/components/bet-details.tsx`): the timeline, the
  Bet facts, then for sports bets **Live stats**: the game's score and clock,
  and each number the mode follows with what it was when the bet was
  proposed, at lock, and now, beside its line or target. Total Disaster
  follows the combined score, Give And Take the home margin, the player modes
  each player's stat (with position and team), and Scorcerer each team's
  touchdowns and field goals. It refreshes every 15 seconds while the game is
  live (`GET /bets/{id}/live`). Bets made before these snapshots show "–" for
  proposed and at lock.

- **Betting:** "+ Bet" opens the proposal sheet: pick a mode (every mode but
  Choose Their Fate, which shows as coming soon), set up a U2Pick, or pick one
  of today's games (NFL only for Scorcerer) and set the terms: the line
  (score modes); the stat, one or two players from that game (listed with
  their position and current number, sorted by position) and the line or target, which starts just above them
  (player modes, `bets/components/player-setup.tsx`); and whether overtime
  counts. Then the wager (+0.01/+0.10/+1.00 steps,
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
  The poll button (shown when the table has polls on and you have the polls
  permission) posts a question with 2–6 options; poll cards show each
  option's count, bar and voters, and tapping an option votes, changes or
  removes your vote.

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
  while you owe nothing, and your password (current password first; other
  devices are signed out), and a Display tab (theme, text size, motion, 24-hour
  clock) saved on this device.
- **Set your password:** `/setup#<code>` (from an admin's setup or reset
  link) sets a password and signs in, signed in or out. The code stays in
  the URL fragment and is removed from the address bar afterwards.
- **How to play:** the game modes, bet lifecycle and payout rules, and a
  feedback form that sends to the backend.
- **Admin:** an admin account sees only admin tools; it can't join tables,
  bet or chat.
  - **Players:** create a player account and show its one-time setup link
    with a QR code, or issue a new setup or reset link. Links are shown once.
  - **Account:** change the admin's password.
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

What you're told about an action is a toast, not text on the page. Wherever
the tab bar shows, toasts sit just above it, never over it (`--toast-bottom` in
`styles.css`). Errors show
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
