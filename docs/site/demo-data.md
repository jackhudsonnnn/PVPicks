# Demo world

::: warning Partly implemented
This page is the agreed design (owner decisions, 2026-11-14), built in the
phases at the end of the page. **Phase 1 is implemented**; seeded history,
live demo bets, looping games and bots (phases 2 and 3) are not yet.
:::

The dev backend always comes up as a lived-in app: about 200 demo users with
their own play styles, three main accounts at different stages, live games
to bet on, and bots that react to what you do. Production never has any of
it. There's no switch: it's the dev profile, and **Reset** on the Dev page is
how you get a clean slate.

## Signing in

There are no dev passwords. In the dev profile, the sign-in page lists quick
buttons for **newbie**, **intermediate**, **pro** and **admin**, plus a search
over every demo user, and signs you in as whoever you pick
(`GET /dev/demo/accounts`, `POST /dev/demo/sign-in`). Production login stays
password-based. `provision-dev.sh --init` now makes only the signing and
retry keys; the old `infra/compose/.env.dev-accounts` file is no longer used.

The demo is on exactly when `pvpicks.demo.enabled` is true, which only
`application-dev.yml` sets (tests turn it off). Demo accounts share one
password hash of a secret that's thrown away, so no password works for them.

## The main accounts

| Account | State after a reset |
|---|---|
| `newbie` | Completely fresh: no friends, follows, tables, tickets, chat or settle-ups. |
| `intermediate` | 9 friends, a few tables, a short settled history. No active or pending bets, no blocks or reports. |
| `pro` | 60 friends and many tables. Blocks people and is blocked by people who share his tables. About 300 tickets over 90 days, with receipts and settle-ups. Kept near **20 pending and 5 active** bets. |
| `admin` | The admin account: moderation queue (seeded reports come in phase 2), site health. |

The pending and active counts can't be fixed numbers: a pending bet's pick
window is 30–600 seconds, and active bets settle when their game ends. Bots
keep proposing in the pro's tables to hold him near 20 pending, and his active
bets sit on demo games that loop (below).

## Demo profiles

Every demo user has a `DemoProfile`. Each parameter has a sensible default,
and an archetype changes only a few of them; each user then gets small random
variation from a fixed seed, so a reset rebuilds the same world.

| Group | Parameter | Default | Meaning |
|---|---|---|---|
| Social | `followBackChance` | 0.7 | Follows you back after you follow them. |
| | `inviteAcceptChance` | 0.6 | Accepts a table invitation. |
| | `followRate` | 0.1/day | Follows someone new on their own. |
| | `blockChance` | 0.02 | Blocks a table-mate after a bad beat or trash talk. |
| | `reportChance` | 0.01 | Reports a message aimed at them. |
| Betting | `joinBetChance` | 0.5 | Picks on a pending bet they can see. |
| | `proposeRate` | 0.3/hour | Proposes a bet in one of their tables. |
| | `modes`, `leagues` | all | Weights for the modes and leagues they bet on. |
| | `pickStyle` | `RANDOM` | `FAVORITE`, `UNDERDOG`, `HOME`, `RANDOM` or `CONTRARIAN` (against the table). |
| | `wager` | 5–20 pts | Wager range when they propose. |
| | `reactionSeconds` | 10–120 | Delay before acting, so nothing happens instantly. |
| Chat | `chattiness` | 0.5/hour | Messages in an active table. |
| | `gifChance`, `reactionChance` | 0.15, 0.2 | GIFs instead of text; reacting to messages. |
| | `trashTalkChance` | 0.3 | A canned line after a win or loss. |
| Money and activity | `settleStyle` | `PROMPT` | `PROMPT`, `SLOW`, `FORGIVES` or `DISPUTES` with receipts. |
| | `activeHours` | 10:00–01:00 ET | When they act. |
| | `onlineChance` | 0.3 | Share of their active hours they're actually around. |

Archetypes (each changes only what's listed):

| Archetype | Changes |
|---|---|
| Casual | Nothing; the defaults. |
| Degen | `joinBetChance` 0.9, `proposeRate` 1.5/hour, `wager` 15–50, `chattiness` 1.5. |
| Lurker | `joinBetChance` 0.2, `chattiness` 0.05, `onlineChance` 0.15. |
| Sharp | `pickStyle` `FAVORITE`, `settleStyle` `PROMPT`, `trashTalkChance` 0.05. |
| Troll | `trashTalkChance` 0.8, `blockChance` 0.1, `reportChance` 0.1, `settleStyle` `DISPUTES`. |
| Friendly | `followBackChance` 0.95, `inviteAcceptChance` 0.9, `settleStyle` `FORGIVES`. |

Usernames are generated handles that fit the 3–15 character rule. Chat lines
are canned, not AI-written.

## Bots

A dev-only loop wakes every few seconds and lets online demo users act through
the same application services real requests use, so every rule (locks, blocks,
invite preferences, settle-up rules) applies to bots too. They act only in
tables that include a main account, which keeps 200 users cheap. They:

- pick on pending bets and propose new ones;
- follow back, follow, accept or decline invitations;
- chat, send GIFs, react, trash talk, and now and then block or report;
- pay, forgive or dispute receipts per `settleStyle`.

## Demo games

The demo auto-starts dev sports data (see [Dev sports data](/dev-sports-data)):

- a few long scripted NBA and NFL scenarios that **loop**, restarting when
  they finish, so there are always live games for active bets;
- the recorded chunks.

This needs `PVPICKS_DEV_SPORTS_DIR` (Compose sets it). Without it, bots still
act, but only on U2Pick and on real games.

In code: `demo/DemoProfile.java` (parameters, defaults, archetypes),
`demo/DemoRoster.java` (the generated users) and `demo/DemoWorld.java` (what
a reset seeds).

## Seeded history

A reset writes the history directly. Bets, picks, ledger entries, settlements
and receipts are kept consistent with each other, backdated over 90 days on
games that look real. Live activity after the reset goes through the services.

## Reset

**Reset data** on the Dev page:

1. stops the bots and the dev sports sources;
2. wipes app data: users, sessions, social, tables, bets, chat, ledger,
   settlements, moderation, feedback and the outbox;
3. keeps sports data (real games, players and stats) so the ticker isn't
   empty;
4. reseeds the world and restarts the games and bots;
5. signs everyone out, you included, and returns you to the sign-in picker.

The backend also seeds an empty database (no users) on startup. Ledger and
settlement tables are append-only and refuse TRUNCATE; reset turns their
user triggers off only inside its own transaction.

## Phases

1. **Roster, sign-in and reset** (implemented): `DemoProfile`, archetypes and the
   generated roster; passwordless sign-in picker; reset; main accounts with
   friends, blocks and tables; remove dev passwords.
2. **History and live bets:** seeded tickets, receipts and reports; looping
   scenarios; the pro's pending and active bets.
3. **Bots:** the live loop.
