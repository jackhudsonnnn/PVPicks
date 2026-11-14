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
  - `auth`: session API, sign-in form;
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
    - A side panel holds Chat, Peers (`peers.tsx`) and Host-or-About (`host-panel.tsx`); on phones it becomes tabs.
    - People open `social/profile.tsx`.
    - Report and Block live in `social/safety.tsx`, with rules in `report-rules.ts`.
    - Live tomatoes play from `tables/components/tomatoes.tsx`.
  - Receipts: `settlements/settlements.tsx` (Settle-ups on Friends: a card per person, including people with only an unreviewed warning, so a warning stays reachable) and `settlements/receipts-dialog.tsx` (warning review, group actions, dated receipts with per-receipt actions). Which actions each side gets, and which wait for a review, live in tested `settlements/receipt-rules.ts`. The unknown-outcome retry block also renders inside the dialog, because the dialog covers the page's copy. Receipt rows name tables through `tables` `useTableNames` (current tables, plus deleted ones for 90 days).
  - Preferences: `preferences/preferences.tsx`, with Tables, Account and Display tabs.
  - How to play, with feedback: `guide/guide.tsx`.
  - The admin view: `admin/admin.tsx`.

  `/me` returns `role`. An `ADMIN` session renders only the admin view, never player screens.
- Bets render as tickets (`bets/components/ticket.tsx`, with details in `bet-details.tsx`). Tap a choice to pick (confirmed), tap it again to clear, and use "Call it" from a locked ticket. New bets go through the proposal sheet (`bets/components/propose-sheet.tsx`):
  - a mode grid, where U2Pick and the score modes (Total Disaster and Give And Take) are live and the rest show "Coming soon";
  - U2Pick setup, or a game picker with a line stepper and an overtime switch (rules and the server-matching question are in tested `bets/sports-model.ts`);
  - a cents-based wager stepper and pick windows from `bets/wager.ts`;
  - a ticket preview.

  What a ticket shows comes from pure, tested `bets/ticket-model.ts`. Who sits at the felt, and the seat chips, come from `tables/seating.ts`. Active bets reveal only your own pick and the pick count; tallies, players and pots appear after lock.
- The server owns every bet rule: modes, locking, picks, results, washes, payouts and the leftover-cent rule. The client only describes and drafts bets.
  - `bets/modes.ts` is the catalog the guide and sheet show. Add a mode there when the server supports it.
  - `bets/u2pick.ts` validates U2Pick drafts and lists the settler rules.
  - `bets/bet-results.ts` decides who may call a result; the server rechecks.
- Score-mode bets settle from the sports feed. Their tickets show the game's live score (`sports/use-ticker.ts`, shared with the score strip). For hosts, they also show an Override menu (wash or set the result) until points are settled. U2Pick results are called by their settler rule instead.

## Data

- Requests go through `lib/api-client.ts`:
  - `request`, `read` (zod), `csrf`, `mutationRequest`;
  - `BackendError`, `backendErrorMessage`, `isUnauthorized`;
  - `idSchema` for every id (don't redeclare `z.guid()`).

  Each feature's `api.ts` validates its responses with zod. API tests share valid payloads from `src/test/api-fixtures.ts`.
- Every signed-in view that reads data calls `useExpireOnUnauthorized([query.error, …], expire)` (`lib/use-session-expiry.ts`), so a 401 returns to sign-in. Don't hand-write the effect. Every query key comes from `lib/query-keys.ts` (`["backend-tables", userId, ...]`) so sign-out clears it.
- Names and avatars come from `people/use-person.ts` (`[...peopleKey, "profile", id]`), cached until a `profile.changed` event invalidates it. Per-table points come from `GET /tables/{id}/balances`, cached under the table's bets key so every bet change refreshes them.
- Mutations go through `useRetryableMutation` (`lib/mutation.ts`), via one action hook per area (each feature's `actions.ts`).
  - An uncertain action's idempotency key is saved per user in localStorage until it is retried or cleared.
  - `ActionStatus` (`components/action-status.tsx`) renders retry and discard.
  - Use one action hook instance per page: two instances with the same storage key in one tab do not sync.

  Why: retries reuse one key across tabs, so an uncertain action can't be applied twice.
- Pure rules for screens live in tested modules, not component files:
  - `bets/ticket-model.ts`, `bets/sports-model.ts`, `bets/bet-results.ts`, `bets/u2pick.ts`;
  - `tables/seating.ts`, `tables/rules.ts` (table names up to 14 characters, addable followers);
  - `social/rules.ts` (people lists, search), `social/profile-model.ts`;
  - `preferences/rules.ts` (debt cap input), `preferences/profile-rules.ts` (usernames, hues);
  - `chat/model.ts` (window merges, reactions), `chat/previews.ts` (card preview lines);
  - `guide/feedback.ts`.

  The server enforces every rule; client copies only give early feedback.
- Live updates go through `lib/realtime-connection.ts` (one STOMP connection, tested) and `lib/realtime.tsx` (`RealtimeProvider`, and `useRealtimeFeed(destination | null, ...)`, where `null` subscribes to nothing). A feed handler only invalidates queries; it never applies message data. Why: invalidations carry no private data, so HTTP stays the source of truth.
- Table chat:
  - `chat/chat.tsx` with `chat/use-chat.ts`: contiguous server windows, one operation at a time. `readOnly` (archives) skips the live feed, the read position, sending and reactions.
  - `chat/gifs.ts` is the GIF catalog, keyed by the IDs the server accepts.
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
