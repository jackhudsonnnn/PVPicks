# Admin dashboard

An admin account sees only the admin workspace. It can't join tables, bet or chat, and player APIs refuse it. To
play, the owner creates a player account for themselves under Users.

The workspace is built for desktop, and still works on a phone. Its layout matches the player app's: a sidebar on
desktop with the four sections (**Users**, **Reports**, **Perf**, **Sports**), sign out beside the logo and
**Change password** at the bottom; on a phone, a top bar (change password, sign out) and a bottom tab bar.

The address bar always holds the current view: tab, player, perf window and route, sports league, day, game and
panel. For example, `/?tab=perf&window=24h&route=GET+/tables/{tableId}`. A copied link or a screenshot of the
address bar opens the same view. Opening a player or a game adds a history entry, so Back returns to the
previous view. Changing a filter replaces the entry instead.

Code lives in `client/src/features/admin/` (one file per tab) and the backend modules listed under
[Endpoints](#endpoints).

## Users

The tab has two sideways rows of cards: **Players**, and below it the **Audit log**. Players are searchable by
username prefix, and each card shows a player's avatar, last sign-in, strikes, open reports, frozen status and sign-in code.
From here an admin can:

- create a player (**New player** opens a dialog), who gets a sign-in code at once;
- see a red banner while any player is locked out of code sign-in (10 wrong codes against them in 15 minutes),
  and a **Locked out** chip on their card, each with **Unlock**;
- show a player's sign-in QR code and code: blurred until **Show** on the QR (the code only reaches the browser
  then). **Shown and selected are one rule:** the selected player's card is green, their QR is shown and their
  audit is open, and only one player is selected at a time. Selecting a player (by name, by Show, or from the audit
  log) shows their QR; tapping the shown QR, or their name again, deselects them and blurs it. **Copy link**
  copies the `/signin#<playerId>.<code>` link (the id, so a rename doesn't break it) even while blurred, without
  showing the code;
- **Rotate** a code (after a confirmation): a new code, and the player is signed out on every device.

Players scan the QR code or type their username and code to sign in, and may photograph it to get back in later. See
[LAN watch party](/lan#accounts-and-sign-in-codes) for how codes are stored and throttled.

The audit log is every audit any admin opened and every search they ran, newest first. Picking a player (from
either row) opens their **audit** underneath. Both rows load 50 more as you scroll toward the end.

The audit shows:

| Section | What it shows |
|---|---|
| Header | Username, id (click to copy), status, strikes, joined, last sign-in and active sessions. A "This view is logged" line with the time read, for screenshots. |
| Headline | Win rate, net points, points settled, rejected sends, reports against them, tables. |
| Activity | Messages, picks, bets proposed and settles per New York day for the last 30 days. These are four small charts, so each keeps its own scale. |
| Record by mode | Won, lost, washed and locked-but-waiting per mode, with a split bar and win % for each. |
| Settle-ups | Settled while down, settled while up, their settles rejected by others, settles they rejected, settles still in the 48-hour reject window, and points limit. |
| Moderation | Report counts and the 10 most recent reports against them and by them, read-only. Names open that player's audit, and **Open Reports** goes to the queue. |
| People | Friends, following, followers, blocking, blocked by. |
| Tables | Each current table: role, joined, members, and their messages, polls, bets, picks and net points there. Also how many deleted tables they were in. |
| Audited by | Collapsed by default; opening it loads every admin audit and search of this player, newest first, 50 at a time (the audit log filtered to them). Placed above the writing search, which can run long. |
| What they wrote | Keyword search over their chat messages and GIFs, polls (question and options) and the bets they proposed (question and choices). Filter by kind, 30 at a time, matches highlighted. |

**Win rate** is won ÷ (won + lost) on settled bets with points. Washed bets and 4fun bets are shown separately
and left out of the rate.

**Privacy.** Bets still taking picks (Active) are left out of every pick and participation count on the page
until they lock, the same rule players live under (see the [domain rules](/reference/domain-rules)). The bets a
player proposed are public in their table as soon as they're proposed, so those count.

**Logging.** Opening an audit, and every search inside one (the first page of a search, not "Load more"), is
written to `admin_audit_log`. The same admin, player and search are logged at most once per 10 minutes, so
refreshes don't flood the log.

## Reports

Two sections:

- **Moderation** is the report queue (a sideways row of cards) and the list of players with strikes or frozen accounts, searchable by username. Admins dismiss a
  report or give a strike; the third strike freezes the account and signs it out. Admins also unfreeze and clear
  strikes.
- **Feedback** is what players sent from How to Play: mode ideas, perks, GIFs, bugs and other, as a sideways
  row of cards. A dropdown picks New, Done or All (with the overall counts) and tabs pick a kind or All. An admin can add a private note, mark an
  item Done (recording who and when) or reopen it.

Every player name, shown with their avatar, opens that player's audit. Lists load 50 at a time, the next page as
you near the end.

## Perf

Built for spotting a slow or failing endpoint and handing it to an engineer as a screenshot.

**Live** refreshes every 10 seconds and shows:

- **Dependencies:** PostgreSQL, Redis, and Kafka (not checked yet), plus each league's feed. A feed shows Up,
  Stale (no success for 15 minutes) or Down with the last error.
- **Sign-in codes:** how many players are locked out or have wrong codes this window, the shared network
  backstop (x of 100), and **Clear all**.
- **Runtime:** uptime, database pool (active of max, idle, waiting), heap, CPU, threads, and the outbox backlog
  with its oldest row's age.
- **Build:** host, version and Java version, with the time read.

The top row has **Source** on the left and **Window** (1h, 6h, 24h or 7d) on the right. **Source** is either:

- **Server:** time spent in the backend per request.
- **Browsers:** what players' browsers measured. That is API calls including the network, plus page loads and
  largest contentful paint, under app routes such as `PAGE /tables/$id`.

For the window it shows:

- **Totals:** requests (and per minute), 5xx, 4xx, 429, p50, p95, p99, max and average. The line above them
  names the source, window, exact range and step. **Copy summary** copies a plain-text version, with the five
  slowest routes and the link, to paste beside a screenshot.
- **Charts:** requests per step, latency (p50, p95, p99), and errors stacked by 5xx, 4xx and 429. Hover for the
  values at a point.
- **Routes:** every route, sortable by requests, 5xx %, p50, p95, p99 and max, and filterable. A route is marked
  **slow** over 1 s p95 and **failing** over 1% 5xx. Clicking a route narrows the totals and charts to it.

How the numbers are kept:

- **Server timings.** `admin.PerfFilter` runs outermost, so security refusals and rate limits are counted too.
  It files each request under its handler's route pattern, such as `/tables/{tableId}/bets`, never the raw path.
  Requests that never reached a handler are filed under `(no route)`. WebSocket, health and docs requests aren't
  timed.
- **Browser timings.** `request()` in `client/src/lib/api-client.ts` records every API call in
  `client/src/lib/perf.ts`, which also records the page load and LCP once per load. The browser sends these to
  `POST /perf` about once a minute and when the tab hides, only while someone is signed in. The server maps each
  path to the server route it matches and drops paths it doesn't know. No response counts as a 5xx.
- **Storage.** `admin.PerfRecorder` adds samples up per minute, source, method and route. It writes finished
  minutes to `perf_minutes` (adding to a row another instance wrote) and deletes rows older than 7 days. Each row
  holds a latency histogram (bounds 5 ms to 10 s), so percentiles are estimates accurate to the bucket.
- **Bounds.** Each minute keeps at most 300 routes per source, so junk reports can't grow the table. A demo reset
  keeps `perf_minutes`.

## Sports

The data the feed stored. A **league dropdown** in the top right (NBA by default) shows each league's game and
live counts; on the left of the same row are the feed's freshness (live count, last update or failure) and, under it, the path (league › day › game). Below are columns: **Day → Game**, then an inspector for the
chosen game.

- **Days** are New York dates (the poller's calendar), newest first, the latest 400. **Today** jumps to the
  current date.
- **Games** show score, status, clock or start time, and how many bets use the game.

The inspector shows a scoreboard and four panels:

- **Score:** the line score by period, when the game starts, first and last change seen, snapshot count, players
  with stats, and bets on the game by mode and status.
- **Timeline:** both teams' scores across every stored snapshot (the latest 5,000), and a slider to step through
  them. Each step shows the state at that moment, what changed from the step before, and the line score then.
- **Players:** current stats for every player, sortable by any stat and filterable by team. Picking a player
  shows the history of one stat across their snapshots, and the snapshots themselves as JSON.
- **JSON:** the whole game (game, players, bets and snapshots) as a collapsible tree. Click a node to select it;
  the bar shows its path, such as `game.players[3].stats.pts`, with **Copy path** and **Copy value**.

Live games refresh every 5 seconds. Values that changed (score, clock, line score, stats) flash briefly. The
scoreboard shows when it was last read.

On a phone the columns become a drill-down, with the breadcrumb above to step back.

**Historic data.** The explorer shows what the poller has seen since it started. Backfilling older games and
seasons from the provider is not built yet (see the [roadmap](/roadmap)); that job will be slow, possibly a day
for a full season.

## Endpoints

All `/admin/**` endpoints are admin-only (`security.SessionSecurity`). Full shapes are in the OpenAPI contract.

| Endpoint | Module | Purpose |
|---|---|---|
| `GET /admin/players?q=&cursor=` | `accounts` | Players by username prefix, with strikes and open reports |
| `GET /admin/users/{id}/audit` | `admin` | The audit (logged) |
| `GET /admin/users/{id}/activity?kind=&q=&cursor=` | `admin` | What they wrote (a search is logged) |
| `GET /admin/audit-log?cursor=` | `admin` | The admin audit log |
| `GET /admin/feedback?status=&kind=&cursor=`, `PUT /admin/feedback/{id}` | `feedback` | The feedback inbox |
| `GET /admin/perf?window=&source=&method=&route=` | `admin` | Stored request performance |
| `GET /admin/perf/live`, `GET /admin/health` | `admin` | Runtime gauges, dependency health |
| `POST /perf` | `admin` | Browser timings (any signed-in account, 10 calls a minute) |
| `GET /admin/sports/leagues`, `/days`, `/games`, `/games/{id}`, `/games/{id}/snapshots`, `/games/{id}/players/{playerId}/snapshots` | `sports` | The sports explorer |
| `GET /admin/reports`, `GET /admin/users/flagged`, and the moderation actions | `moderation` | The moderation queue |
