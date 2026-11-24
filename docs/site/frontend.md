# Frontend

The app (`src/app/app.tsx`) is the same in every mode; it asks the backend
which one it's in (`GET /config`, below). From 1100px wide (the `nav` breakpoint) a sidebar holds Tickets, Friends, Home, Guide and
Preferences, your tables and table requests (below), and your balance (every table plus open settle-ups, `GET /me/balance`); narrower screens get a top bar
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

- **Signing in:** the sign-in page asks the backend's mode (`GET /config`,
  `lib/app-mode.ts`): in dev it shows the demo account picker, otherwise it
  asks for the player's username and sign-in code. The admin's username and
  password form is at `/admin` (`auth/admin-sign-in.tsx`), in every mode, and
  nothing links to it. Opening a player's QR link (`/signin#<playerId>.<code>`) signs
  them in at once, signed in or out; the link is removed from the address
  bar. An old code-only link (`/signin#<code>`) is refused. While the backend is
  unreachable (a restart, say), it keeps checking the mode every 3 seconds and
  shows the right sign-in when it answers. Every sign-in starts on Home, whatever URL the
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
  - A newcomer (no friends, no tables and no tickets, once all three have
    loaded) gets **Add friends first** from "New table" and "All tickets"
    instead: a dialog explaining that P2Picks is played with friends, with a
    Find friends button to the Friends page (`components/find-friends-dialog.tsx`,
    `tables/newcomer.ts`).
  - Below the row are deleted tables whose chat you can still read for 90
    days (read-only, at `/archives/<id>`).
- **Friends:** settle-ups first, as a sideways-scrolling row with one card
  per person you have open receipts with (or a send still in its reject
  window).
  - A card shows your open net with that person ("You're down" / "You're
    up" / "You're even") and how many tables and open receipts that
    covers. It's **yellow** when something needs you (a balance change to
    review, or a send to you that you can still reject; the color says it,
    with no extra text on the card), otherwise **red** when you're down,
    **green** when you're up and **neutral** when even. Cards sort
    yellow, red, green, neutral (`settlements/ledger-model.ts`).
  - **Point Ledger** opens the Ledger dialog (`settlements/ledger-dialog.tsx`), headed
    "You're down X with name", "You're up X with name" or "You're even with
    name", with two tabs:
    - **Settle:** any balance change to review ("Got it"), then the open
      receipts with checkboxes and Select all. A large, bold line says what
      the selection comes to ("You're down X", "You're up X", "These even
      out", or "Choose receipts to settle."). The selection's net decides
      the button: **Settle X** when you're down (they can reject it for 48
      hours), or **Mark X as settled** (or **Settle even**) when you're up or
      even. It asks to confirm. There is no Forgive, and nothing mentions
      paying: points have no cash value.
    - **History:** every settle, newest first, a page at a time: who settled
      or marked as settled how much, over how many receipts, and whether it
      was rejected or can still be. The other person gets **Reject** on a settle
      for 48 hours. It opens on History when you have one to reject.
- Below settle-ups, **My network** (same heading style), your people in tabs
  (`social/friends.tsx`), each newest first and without counts: **Find**
  (username search from 3 characters, sent once typing pauses for 500 ms; the tab it opens on), **Followers** (everyone who follows
  you: follow someone back to be friends), **Following** and **Blocked**.
  Friends are people in both Followers and Following, so there's no separate
  Friends tab. A line under the tabs, in the same style as the
  settle-ups description, explains each one. Every list, search results
  included, is a sideways row of cards the size of the settle-up cards,
  mounting 20 at a time as you scroll. A card shows the person and what they
  are to you, with full-width Follow or Unfollow and Block or Unblock
  buttons, each asking to confirm. A host can only add people who follow them, and each person's
  invite preferences decide whether they join right away or get an
  invitation.
- **Table page:** a card-room felt with the focused bet's players seated
  around it, from the moment they join (you sit only if you're in; with no
  bet, everyone at the table), each showing "In" while it's open (your own
  pick for you) and their pick once it locks and the open bets fanned as cards in
  the middle; tap the front card to open its ticket. With more people than
  seats, the last seat is "+N others": it opens the focused ticket's Details
  when the felt shows that ticket's players, otherwise a list of who didn't
  fit (`others-dialog.tsx`). The felt and the Peers tab are independent. A side panel beside the felt from 1455px wide (the `split`
  breakpoint; tabs below that, where both get crowded) holds Chat, Peers (everyone's balance at
  the table; a name opens the profile, a balance opens a what-if dialog that
  lists that member's tickets in play with a "Win?" checkbox each, plus All win
  and All lose) and Host (or About for members): points
  settlement, members, roles, permissions and table settings. A table can
  have up to 500 people, so Peers, the members list and the followers to add
  mount 20 rows at a time as you scroll. A Peers search asks the server for
  members whose username matches (`GET /tables/{id}/members?q=`), 50 at a
  time, so only those rows load. The page loads only the table's live bets; older
  ones load when opened. Tapping a person
  shows their record at the table (computed by the server) and their bets
  there, newest first, a page at a time, and your receipts with them at that
  table: every open one, then closed ones 20 at a time.
- **Tickets:** every bet at your tables as tickets, by status (Active,
  Pending, Resolved, Washed; it opens on Active), optionally for one table (a
  dropdown under the status tabs: full width on phones, at the right on wider
  screens), paged. Scrolled away from the top, the list holds still
  (`bets/held-feed.ts`): new tickets aren't slotted in above you (which would
  shift everything and reflow the two columns), only counted in a pinned
  "↑ N new tickets" pill; they appear once you're back at the top, or when the
  pill scrolls you there. Tickets already shown keep updating in place, and
  ones that leave the tab stay until then. Until there's a bet at one of your tables
  (`GET /me/bets?limit=1` is empty), the Tickets entries in the sidebar and
  phone tab bar and a table's Tickets button are greyed out and don't link;
  the page itself still loads if you go to it directly. A status tab with no
  tickets (for the chosen table) is greyed out; if the one you're on empties
  (your last Active ticket locks), the page moves to the next tab with
  tickets, or the previous one if none follow (`features/bets/ticket-tabs.ts`;
  one `limit=1` request per status). Tickets, Friends and How to play have no visible page
  title (each keeps a screen-reader heading). Under the question a ticket shows "Proposed by" with the
  proposer's avatar and name (opening their profile at that table,
  `?profile=<id>`); the time is in Details. The footer shows the table's emoji
  and name (linking to it) beside the wager and the participants (the pot
  once it shows), on every ticket. Clocks stop at 0:00 when time runs out
  rather than switching to a word.
- **Ticket Details** (`bets/components/bet-details.tsx`): the timeline, the
  Bet facts, then for sports bets **Live stats**: the game's score and clock,
  and each number the mode follows with what it was when the bet was
  proposed, at lock, and now, beside its line or target. Total Disaster
  follows the combined score, Give And Take the home margin, the player modes
  each player's stat (with position and team), and Scorcerer each team's
  touchdowns and field goals. It refreshes every 15 seconds while the game is
  live (`GET /bets/{id}/live`). Once the ticket is resolved or washed the
  section becomes **Result**: the server froze the numbers, score and clock
  when it was called (`closed`), "Now" becomes "Result", and it stops
  refreshing. Bets made before these snapshots show "–" for
  proposed and at lock.

- **Mode info** (owner decision, 2026-11-23): the setup form is controls
  plus the numbers that change with them (worst case, buy-in) and one-line
  switch states; every explanation lives in the mode's info, the (i) beside
  its name in the sheet's header, which swaps the setup for How it works, 2–3
  worked Examples (their own settings and results) and Good to know
  (`bets/components/mode-info.tsx`, content in `bets/mode-info.ts`). The
  Guide's Game modes cards open the same info in a dialog (`ModeInfoButton`).
- **Betting:** "+ Bet" opens the proposal sheet (`propose-sheet.tsx`; a
  sports bet's state lives in `use-sports-draft.ts` and its form in
  `sports-setup.tsx`): pick a mode (every mode but
  Choose Their Fate, which shows as coming soon), set up a U2Pick, set up a
  Jackblack game (its rules, each held to its minimum and maximum; no preview
  step), or pick one
  of today's games and set the terms: the line
  (score modes); the stat, one or two players from that game (listed with
  their position and current number: the stat's positions first, most
  relevant first, each by its current value (ties, as before kickoff, by season average),
  then everyone else under an "Other players" divider; NBA stats by value
  alone; `sports/roster-order.ts` `forStat`; each shows its season average
  when there is one) and the line or target (player modes,
  `bets/components/player-setup.tsx`); and whether overtime counts. A line or
  target starts at the season average (`suggestedLine`) and can't be set at or below what's already happened (the slider's Min
  is just above the players' current numbers, or a live game's combined score
  for a total), and it follows them up while you set it, so nothing proposed
  washes at lock for being already decided; a "Now:" line shows the numbers. Lines end in .5 and targets are whole numbers; both step by
  one (the step shows under − and +), in the shared slider widget
  (`ValueSlider`: the value large, the slider, then Min, −step, +step and Max,
  all tappable). Its Max is the ceiling: 399.5 for totals, about 600 for yard
  stats and 100 for other player stats. Then the wager (a slider, 0 to 10.00 in 0.10 steps; 0 reads "(4fun)" and sends a 4fun bet) and
  the pick window, preview the ticket (sports bets add **Right now**: the
  score and clock and the numbers the bet follows, `game-now.tsx`), send.
  A pending sports ticket shows its progress above the byline
  (`ticket-progress.tsx`, the same live stats as Details): a bar toward the
  line or target, a tug between Either Or's two players, or Give And Take's
  margin and who's covering.
  **Resend** on a resolved or washed ticket (`resend-button.tsx`) opens its
  table's proposal sheet filled in with the same terms (`?resend=<bet id>`);
  anyone who may propose there can, and it's checked like any new bet, so a
  game that's over or a line that's been passed is refused with the reason. Jackblack's form uses the same
  layout: its Game and Setup sections (`jackblack-setup.tsx`), then the same
  wager slider as the ante (with each player's worst case under it) and the
  same window tabs as the join window. On a
  ticket, tap a choice to pick (you confirm first), tap it again to clear it,
  and use "Call it" on a locked ticket if its settler rule allows you. Sports
  tickets show the game's score and settle from the feed; hosts can override
  them (wash or set the result) until the table's points are settled.
  Jackblack and Hold'em are played on their tickets
  (`bets/components/jackblack-ticket.tsx`, `holdem-ticket.tsx`), on a shared
  frame (`card-ticket-shell.tsx`, owner decisions 2026-11-23: the felt, cards,
  tiles, join button and timer ring both games use; card faces, timers and
  result chips are in `bets/cards.ts`, and Details' sections, rows and shuffle
  audit in `details-parts.tsx`) that keeps the
  card the same height from the join window to the final standings. Under the
  title is the game's progress ("Round 2 / 7", "Hand 3 / 9"; 0 and a total from
  who's in while the join window is open), and, under the perforation like every ticket, the byline: Proposed by, then Resend (once it's finished) and Details (`ticket-byline.tsx`). The felt has
  four fixed regions in every phase: the table (the shoe and the dealer's hand;
  the deck, board and pot), a row of people that scrolls sideways (who's in;
  your seat, or every seat when you deal, and in Hold'em your hand then every
  seat with stack, position and To act / Folded / All in / Bet, the player to
  act sliding into view; between rounds or hands everyone's result; at the end
  the standings), one status line, and a controls bar (Join / Leave, Hit /
  Stand / Double / Split, the dealer's button, Fold / Check or Call / Raise,
  the rebuy; once it's finished, Run it back, then Join the rematch or You're in the rematch with its countdown, `RunItBack`), empty when there's nothing to press. Whoever's decision clock is
  running has a ring round their avatar that drains (red in the last quarter;
  `TimerRing`). Hold'em names your best hand under your cards once the flop is
  out (`me.handName` from the server), offers pre-moves while it isn't your
  turn (Check/Fold, Call any; kept on the server, `me.preMove`, and played
  there the moment your turn arrives, ticket open or not), and Raise takes over the felt below the board (seats,
  status and moves, at their exact height, so the card never changes size):
  quick sizes (Min, ½ Pot, Pot, All in) with an X to cancel, the slider, and
  the confirm button. Rules, the shuffle audit and every
  round or hand are in Details, not on the card. Its footer is every ticket's
  (`ticket-footer.tsx`): the table link, then Ante or Blinds and Participants
  (who's in the game).
  The games read `GET /bets/{id}/jackblack` and `/holdem`, refreshing with the
  table's bet feed and every 4 seconds while they're live. The table felt stays
  light (`bets/components/jackblack-felt.tsx`, `holdem-felt.tsx`): the usual
  ticket stack, its pill the same for every mode and stage, "N in · i/total"
  (players in, then this ticket's place among the table's pending and active
  tickets), pulsing when it's your move; the mini card shows the felt in
  every stage, from the join window on, with the dealer's cards
  (`JackblackMiniHand`) or the board (`HoldemMiniBoard`), and cards not yet
  turned over shown face down (on the ticket too); and chips
  with your own total (every seat's when you deal; others show "Deciding",
  "Ready" or "Playing"), "Dealing" for the round's dealer, and results at the
  end (`useJackblackSeatChips`). Details shows Jackblack's
  own sections (`jackblack-details.tsx`): Game, Rules, a Shuffle audit
  (each round's fingerprint; once the game is over, its deck count and a
  check, run in your browser, that it matches and that the dealt cards are the
  top of that shoe), Players with their totals, and every finished round's
  dealer and seat results. Hosts choose who can
  propose it under "Who can propose Jackblack" in the Host panel. Once dealt,
  the felt seats only its players, and a finished game stays on the felt for
  20 seconds (`FINISHED_HAND_MS`).
  Creating does not place a pick. Active tickets show only your own pick and
  the pick count; picks, players and the pot appear after lock.

- **Chat:** each table has a Bets/Chat tab pair. Chat loads at your first
  unread message (marked "New") with at most one page after it, pages older
  and newer history on request, and Jump to latest opens the newest window
  instead of paging through a backlog. Live updates fold a burst of changes
  into one refresh, and a refresh more than two pages behind opens the newest
  window too, so a busy table like Mosh Pit stays responsive. It sends text (1–2000
  characters) and GIFs from the server's library (picked in a modal like the
  poll composer: search, a Starred tab first, then Hype, Pain, React and
  Roast, with a star on each GIF; `chat/gif-dialog.tsx`), toggles one reaction per
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
  (also your sign-in name), avatar color and points limit, which can only change
  while you're not down (players have no password), and a Display tab (theme, text size, motion, 24-hour
  clock) saved on this device.
- **How to play:** the game modes, bet lifecycle and payout rules, and a
  feedback form that sends to the backend.
- **Admin:** an admin account sees only the admin workspace (Users, Reports,
  Perf, Sports); see [Admin dashboard](/admin).
- **Account deletion:** Preferences → Account → "Delete my account", typing
  your username to confirm. It's refused, with the reason, until you're square (no open
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
`styles.css`). Each toast stays 7 seconds and has an x in its top-right
corner to close it sooner (`components/ui/sonner.tsx`). Errors show
as toasts; successes are silent (the screen updating is the confirmation)
unless there's something worth saying, like "Unblocked. Follows are not
restored." Loading never shows text either: lists, pages, chat, the ticker and
names show grey placeholders shaped like what's coming
(`components/loading.tsx`), and busy buttons show a spinner. The empty state
("Nothing here yet") only appears once a list is known to be empty.

## Source layout

Code is organized by feature; this section is the rule for what lives where. Code that differs by mode (dev, LAN,
prod) asks `useAppMode()` (`lib/app-mode.ts`) only where it must: the sign-in screen and the admin's Users tab.
See [the three modes](/architecture#three-modes-dev-lan-prod).

- `src/app/`: the frame. Session, sign-in routing, the shell, and the realtime
  connection's lifetime.
- `src/features/<name>/`: one folder per product area:
  - `auth`: session, sign-in (the player's, and the admin's at `/admin`) and the demo account picker;
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
  the HTTP client, retryable mutations, query keys, the realtime client,
  storage and small hooks such as `use-debounced.ts` (search boxes). They never
  import features. In `components/segmented-tabs.tsx`, a tab's `disabled` only
  blocks it (for example while a change saves); `soon` greys it out with a
  "Soon" tag.
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
[Demo world](/demo-data)). Players sign in with codes; the demo admin's
password is `demo`, for the admin form.

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
