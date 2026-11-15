# Project rules

## Layout and boundaries

- Code is organized by feature:
  - `src/app/` is the frame;
  - `src/features/<name>/` holds the product areas;
  - `src/components/` and `src/lib/` are shared;
  - `src/routes/` holds path stubs;
  - `src/test/` holds test helpers.

  `npm run lint` enforces the boundaries:
  - Code outside a feature imports it only through `@/features/<name>` (its `index.ts`).
  - Inside a feature, use `./x` in the same folder and `@/features/<own>/…` across its subfolders, never `../`.
  - Features never import `@/app`.
  - `lib` and `components` never import features or the app.
  - Tests may import any module, so they can `vi.mock` the exact module that defines a function. Mock the defining module (for example `@/features/tables/api`), not the barrel.
  - Unused variables and imports are errors (prefix a deliberately unused name with `_`).

  Why: each area stays findable and replaceable, and its public surface is explicit.
- Inside a feature, files are flat and named for what they hold:
  - `api.ts`: zod-validated reads and requests;
  - `actions.ts`: mutations;
  - `rules.ts` / `*-model.ts`: pure, tested rules;
  - one file per screen or panel.

  Tests sit beside their file. Use a `components/` subfolder only past about 15 files (`bets` and `tables` have one). When another feature needs something, export it from the feature's `index.ts`. Remove an export from it when nothing outside the feature uses it, and delete code nothing uses. Barrels re-export only; never import your own feature's barrel.
- Features:
  - `auth`: session API, sign-in form, and the demo's passwordless account picker (shown when `GET /dev/demo/accounts` answers; a failed check keeps retrying);
  - `people`: user and profile reads, `PersonName`, avatars, `usePerson`, hue colors;
  - `tables`: table API and actions, home, table list, the table page and felt, Peers, Host/About, archives, tomatoes, seating;
  - `bets`: bet API and actions, tickets, details, the proposal sheet, the Tickets page, ticket, sports and U2Pick rules, the mode catalog;
  - `chat`: chat API, window model, reactions, GIF catalog;
  - `social`: friends, profiles, report and block, invite modes;
  - `settlements`: receipts;
  - `sports`: ticker API and strip, `useTicker`;
  - `preferences`: preferences, display settings, username and hue rules;
  - `guide`: How to play, feedback;
  - `admin`.

## App

- The app is backend-backed only. `__root.tsx` renders `SettingsProvider` → `App` (`app/app.tsx`) → `ErrorToasts` for every path; `App` routes by pathname. Route files under `src/routes` are stubs that only provide the path and page metadata (`component: () => null`). Never add mock or sample data as a substitute for unavailable server data. The earlier mock-store prototype is preserved at the git tag `prototype-ui` as the design reference. Port its components as prop-driven views and don't restore its rules, because the server owns them. Why: HTTP is the only source of truth.
- Screens render inside the app shell (`app/shell.tsx`). On desktop it has a sidebar with navigation, your tables and net settle-up points. On phones it has a top bar and tab bar. The ticker shows everywhere except table pages.
  - Home: `tables/components/home.tsx`. Invitation and table cards are in `table-cards.tsx`: one horizontal snap-scrolling row, with the invitation "Accept?" dialog and one social action hook for every card. "New table" is `create-table-dialog.tsx`. Deleted tables are in `archives.tsx`. A card's last-message line comes from `chat/previews.ts`: `useChatPreview` (one shared `GET /me/chat/latest` query, refetched on each card's table chat feed) and `previewText`. Green "in play" cards come from `bets/in-play.ts` (every page of `GET /me/bets?status=ACTIVE`). Each table card watches its table's bets feed and refreshes everything under the "my-bets" key. Desktop Home adds `bets/components/home-tickets.tsx` (live-first order in tested `bets/ticket-feed.ts`). It is mounted only at the `lg` breakpoint (`lib/use-media-query.ts`), so phones don't fetch it. Paged lists use `components/load-more.tsx`, which also loads on scroll.
  - Tickets: `bets/components/tickets-page.tsx`. It pages through `GET /me/bets` with status tabs and `?table=`.
  - Friends: `social/friends.tsx`.
  - The table page: `tables/components/table-page.tsx`.
    - The felt is in `felt.tsx`, with seats and the open-bet deck.
    - A side panel holds Chat, Peers (`peers.tsx`) and Host-or-About (`host-panel.tsx`); on phones it becomes tabs. The Host panel's sections live beside it: `host-settle.tsx`, `host-table-settings.tsx` and `host-members.tsx`, with shared types and settings in `host-shared.ts`.
    - The table page loads only the live bets (`GET /tables/{id}/bets`: Active, Pending, and resolved bets awaiting points settlement). A ticket opened for an older bet (from chat or a profile) is fetched by `bets/history.ts` `useBet`.
    - People open `social/profile.tsx`. It shows the server's record for them at the table (`useMemberRecord`) and their bets there, newest first and paged (`useMemberBets`).
    - Report and Block live in `social/safety.tsx`, with rules in `report-rules.ts`.
    - Live tomatoes play from `tables/components/tomatoes.tsx`.
  - Receipts: `settlements/settlements.tsx` (Settle-ups on Friends) and `settlements/receipts-dialog.tsx` (the warning review, group actions, and dated receipts with per-receipt actions).
    - One request feeds both: `GET /people/settle-groups?includeHistory=true`. Each group carries its receipts, any unreviewed warning (`pendingReviewRevision`), and the viewer's `actions`.
    - Each group and receipt lists the actions the server allows the viewer (`actions: [{action, blockedByReview}]`). The client only labels them (`settlements/receipt-rules.ts`) and disables the ones waiting on a review.
    - The unknown-outcome retry box also renders inside the dialog, because the dialog covers the page's copy.
    - Receipt rows name tables through `tables` `useTableNames`: current tables, plus deleted ones for 90 days.
  - Preferences: `preferences/preferences.tsx`, with Tables, Account and Display tabs.
  - How to play, with feedback: `guide/guide.tsx`.
  - Dev data: `devdata/dev-page.tsx` at `/dev`. `app/dev-links.tsx` (flask for the Dev page, document for the docs at `/docs/`, which Vite proxies) sits beside the logo in the shell and on the sign-in page whenever `useDevAvailable` says `GET /dev/sports` or `GET /dev/demo` answers (both 404 outside dev). Signed out, `/dev` renders the Dev page (`SignedOutDev` in `app/app.tsx`, scope `"guest"`, keys from `devKey` outside the user's keys); the backend permits `/dev/**` without a session in dev. The page shows the demo world (counts, bot activity, Reset) and the dev sports sources. Ticker games carry `dev`, and DEV games are marked in the ticker and the game picker.
  - The admin view: `admin/admin.tsx`, with tabs for Moderation (`admin/moderation.tsx`: the report queue and frozen or struck players) and Site health. Admin actions are plain requests, not retryable mutations: each is one-shot on the server, so a repeat answers "already handled".
  - Account deletion: `preferences/delete-account.tsx` (blocker wording in tested `preferences/delete-rules.ts`). `BackendError` keeps the raw 403/409 body for errors that carry more than a code.

  `/me` returns `role`. An `ADMIN` session renders only the admin view, never player screens.
  Every sign-in (`App` `startSession`) navigates to `/` first: the URL may be the last session's page, possibly another account's. The demo reset runs in `App` (`resetWorld`) behind a full-screen "Resetting" state, so nothing reads as an expired session halfway through.
- Bets render as tickets (`bets/components/ticket.tsx`, with details in `bet-details.tsx`). Tap a choice to pick (confirmed), tap it again to clear, and use "Call it" from a locked ticket. New bets go through the proposal sheet (`bets/components/propose-sheet.tsx`):
  - a mode grid, where U2Pick and the score modes (Total Disaster and Give And Take) are live and the rest show "Coming soon";
  - U2Pick setup, or a game picker with a line stepper and an overtime switch (rules and the server-matching question are in tested `bets/sports-model.ts`);
  - a cents-based wager stepper and pick windows from `bets/wager.ts`;
  - a ticket preview.

  What a ticket shows comes from pure, tested `bets/ticket-model.ts`. Who sits at the felt, and the seat chips, come from `tables/seating.ts`. Active bets reveal only your own pick and the pick count; tallies, players and pots appear after lock.
- The server owns every bet rule: modes, locking, picks, results, washes, payouts and the leftover-cent rule. The client only describes and drafts bets.
  - `bets/modes.ts` describes each mode (name, tagline, icon) for the guide and sheet. Which modes can be proposed comes from `GET /modes` (`useModeAvailability`). The sheet's first step is `bets/components/mode-grid.tsx`.
  - `bets/u2pick.ts` validates U2Pick drafts and lists the settler rules.
  - What the viewer may do with a bet comes from the server: `viewerCanCall` (call a U2Pick result) and `viewerCanOverride` (a host on a sports bet). Never re-derive these permissions on the client. The call and override menu is `bets/components/outcome-menu.tsx`.
  - Status names and wording (badge, Tickets tabs, details, seat chips, chat cards, guide) come from `bets/statuses.ts`.
- Score-mode bets settle from the sports feed. Their tickets show the game's live score (`sports/use-ticker.ts`, shared with the score strip). For hosts, they also show an Override menu (wash or set the result) until points are settled. U2Pick results are called by their settler rule instead.

## Data

- Requests go through `lib/api-client.ts`:
  - `request`, `read` (zod), `csrf`, `mutationRequest`;
  - `BackendError`, `backendErrorMessage`, `isUnauthorized`;
  - `idSchema` for every id (don't redeclare `z.guid()`).

  Each feature's `api.ts` validates its responses with zod. API tests share valid payloads from `src/test/api-fixtures.ts`.
- Every signed-in view that reads data calls `useExpireOnUnauthorized([query.error, …], expire)` (`lib/use-session-expiry.ts`), so a 401 returns to sign-in. Don't hand-write the effect. Every query key comes from `lib/query-keys.ts`: `userKey(userId, ...)` builds `["backend-tables", userId, ...]` so sign-out clears it, and `sessionKey` is the signed-in account.
- Names and avatars come from `people/use-person.ts` (`[...peopleKey, "profile", id]`), cached until a `profile.changed` event invalidates it. Per-table points come from `GET /tables/{id}/balances`, cached under the table's bets key so every bet change refreshes them.
- Mutations go through `useRetryableMutation` (`lib/mutation.ts`), via one action hook per area (each feature's `actions.ts`).
  - An uncertain action's idempotency key is saved per user in localStorage until it is retried or cleared.
  - `ActionStatus` (`components/action-status.tsx`) is the one "outcome unknown" box: retry the same action, or clear it after checking. Every area passes its own wording; don't write another.
  - Action outcomes are toasts, not page text: `useRetryableMutation` toasts a known error, and toasts success only when `execute` returns a note worth saying (otherwise the refreshed screen is the confirmation). Screens with their own "done" state read `succeeded`. Only the uncertain-outcome box stays inline, because it needs its buttons.
  - Loading never shows text: use `components/loading.tsx` (`LoadingList`, `LoadingPage`, `LoadingChat`, `LoadingChips`, `LoadingText`, `LoadingScreen`, and `Spinner` in busy buttons), shaped like what's coming. Don't show the empty state while loading; it claims there's nothing.
  - Tests render the real toaster (`src/test/setup.ts`), so assert toast text with `findByText` like any other text.
  - Use one action hook instance per page: two instances with the same storage key in one tab do not sync.

  Why: retries reuse one key across tabs, so an uncertain action can't be applied twice.
- Pure rules for screens live in tested modules, not component files:
  - `bets/ticket-model.ts`, `bets/sports-model.ts`, `bets/bet-results.ts`, `bets/u2pick.ts`, `bets/ticket-feed.ts`;
  - `tables/seating.ts`, `tables/rules.ts` (table names up to 14 characters, addable followers);
  - `social/rules.ts` (people lists, search);
  - `preferences/rules.ts` (debt cap input), `preferences/profile-rules.ts` (usernames, hues);
  - `chat/model.ts` (window merges, reactions), `chat/previews.ts` (card preview lines);
  - `guide/feedback.ts`.

  The server enforces every rule; client copies only give early feedback.
- Live updates go through `lib/realtime-connection.ts` (one STOMP connection, tested) and `lib/realtime.tsx` (`RealtimeProvider`, and `useRealtimeFeed(destination | null, ...)`, where `null` subscribes to nothing). A feed handler only invalidates queries; it never applies message data. Why: invalidations carry no private data, so HTTP stays the source of truth.
- Table chat:
  - `chat/chat.tsx` with `chat/use-chat.ts`: contiguous server windows, one operation at a time. `readOnly` (archives) skips the live feed, the read position, sending and reactions. Message rows are in `chat/messages.tsx`, and the input and GIF picker in `chat/composer.tsx`.
  - Bet cards render from the summary the server embeds in each bet message (`bet: {question, modeId, status}`), so chat never needs the table's bet list, including in archives.
  - The GIF library comes from the server (`GET /gifs`, `chat/gifs.ts` `useGifs`). Sending accepts only its ids.
  - `chat/reactions.ts` holds the quick reactions; the server toggles them.
- The score strip is `sports/ticker.tsx` over `sports/api.ts`. `sports/ticker-strip.ts` repeats games in whole cycles so the loop seam stays continuous. A null ticker means unavailable and must never render as "no games".

## UI conventions

- Menus that open dialogs (like the chat's ⋯ menu) use `DropdownMenu modal={false}`, or the two focus traps fight.
- Display settings (theme, text size, motion, 24-hour clock) stay with the device.
  - `preferences/settings-provider.tsx` applies them to `<html>` and saves them under `SHARED_KEYS.display` only when the user changes one.
  - It owns the app-wide `MotionConfig`; don't add nested `MotionConfig`s.
  - All browser storage goes through `lib/storage.ts` (SSR-safe, never throws).
- Every scrollable region uses `ScrollContainer` (`components/scroll-container.tsx`); never hand-write `overflow-*-auto` in feature code. Growing lists use `AnimatedList`/`AnimatedItem` (`components/animated-list.tsx`); re-key the list when the whole data set swaps. Animations use `motion` (`motion/react`) and keep a stable DOM order. Why: one consistent scroll and list-motion behavior.
- Every tab strip, filter and single-choice toggle uses `SegmentedTabs` (`components/segmented-tabs.tsx`). On/off settings use `Switch`, whose CSS slide is disabled by the `reduce-motion` class on `<html>`. Settings pages use `SettingsSection`/`SettingRow` (`components/settings.tsx`). Why: one consistent toggle look and animation rule.
- Non-React failures report through `lib/error-bus.ts`, and `ErrorToasts` displays them. Don't silently discard errors; show request errors in context with `backendErrorMessage`.
