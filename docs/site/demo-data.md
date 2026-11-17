# Demo world

The dev backend always comes up as a lived-in app: 200 demo users with their
own play styles, three main accounts at different stages, games that are
always live, and bots that react to what you do (owner decisions,
2026-11-14). Production never has any of
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
`application-dev.yml` sets (tests turn it off). Every demo account's password
is **`demo`**, for flows that ask for one (deleting your account) and the
password form. It's public on purpose: the demo exists only in dev.

## The main accounts

| Account | State after a reset |
|---|---|
| `newbie` | Completely fresh: no friends, follows, tables, tickets, chat or settle-ups. |
| `intermediate` | 9 friends, 3 tables, 8 settled tickets from the last 45 days, all settled up. No Active or Pending bets, no blocks or reports. |
| `pro` | 60 friends and 14 tables (2 more inviting him). Blocks people and is blocked by people who share his tables. About 300 tickets over 90 days, points settled every two weeks per table, older receipts mostly paid (some forgiven, some still open), the latest open. Kept near **20 Pending and 5 Active** bets. |
| `admin` | The moderation queue with 6 open reports, and site health. |

Active means a bet is still taking picks (a 30–600 second window); Pending
means it's locked and waiting for its result. Neither count can be fixed, so
the bots hold them:

- **5 Active:** when fewer than five bets are open in the pro's tables, a demo
  user there proposes one.
- **20 Pending:** a reset seeds 20, locked over the last few hours: U2Picks,
  and every third one a score bet on a live demo game. Demo users call U2Pick
  results 5–30 minutes after lock, but hold back bets the pro is in while he
  has 20 or fewer Pending. Score bets settle from their games, so the count
  dips when a demo game ends and climbs as you join bets.

## Demo profiles

Every demo user has a `DemoProfile`. Each parameter has a sensible default,
and an archetype changes only a few of them; each user then gets small random
variation from a fixed seed, so a reset rebuilds the same world.

| Group | Parameter | Default | Meaning |
|---|---|---|---|
| Social | `followBackChance` | 0.7 | Follows you back after you follow them. |
| | `inviteAcceptChance` | 0.6 | Accepts a table invitation. |
| | `followsPerDay` | 0.1 | Follows someone new on their own. |
| | `blockChance` | 0.02 | Blocks a table-mate whose trash talk they saw. |
| | `reportChance` | 0.01 | Reports that trash talk. |
| Betting | `joinBetChance` | 0.5 | Picks on an open bet they can see. |
| | `proposalsPerHour` | 0.3 | Proposes a bet in one of their tables. |
| | `modes`, `leagues` | all | Modes and leagues they propose (`u2pick`, `total_disaster`, `give_and_take`). |
| | `pickStyle` | `RANDOM` | `FAVORITE`, `UNDERDOG`, `HOME`, `RANDOM` or `CONTRARIAN` (against the table). |
| | `wager` | 1–5 pts | Wager range when they propose (the bet rule caps it at 10). |
| | `reactionSeconds` | 10–120 | Delay before acting, so nothing happens instantly. |
| Chat | `messagesPerHour` | 0.5 | Messages in each of their tables while online. |
| | `gifChance`, `reactionChance` | 0.15, 0.2 | GIFs instead of text; reacting to messages. |
| | `trashTalkChance` | 0.3 | A canned line after a win or loss. |
| Money and activity | `settleStyle` | `PROMPT` | `PROMPT`, `SLOW`, `FORGIVES` or `DISPUTES` with receipts. |
| | `activeHours` | all day | When they act (New York time; the same start and end means all day). |
| | `onlineChance` | 0.9 | Share of their active hours they're actually around. |

Archetypes (each changes only what's listed):

| Archetype | Changes |
|---|---|
| Casual | Nothing; the defaults. |
| Degen | `joinBetChance` 0.9, `proposalsPerHour` 1.5, `wager` 5–10, `messagesPerHour` 1.5. |
| Lurker | `joinBetChance` 0.2, `messagesPerHour` 0.05. |
| Sharp | `pickStyle` `FAVORITE`, `settleStyle` `PROMPT`, `trashTalkChance` 0.05. |
| Troll | `trashTalkChance` 0.8, `blockChance` 0.1, `reportChance` 0.1, `settleStyle` `DISPUTES`. |
| Friendly | `followBackChance` 0.95, `inviteAcceptChance` 0.9, `settleStyle` `FORGIVES`. |

Usernames are generated handles that fit the 3–15 character rule. Chat lines
are canned, not AI-written.

## Bots

`demo/DemoBots.java` wakes every `pvpicks.demo.bots.tick` (5 s) and lets
demo users act through the same services real requests use. Each bot signs in
for a real session (`PasswordlessSignIn.session`), so every rule applies to
it: pick windows, permissions, blocks, invite preferences, debt caps and
settle-up reviews. Online status comes from `activeHours` and `onlineChance`,
and holds for 15-minute stretches. Decisions are made once per thing (a bet,
a follow, an invitation) and carried out after the bot's reaction delay. What
they do:

| When | What | Where |
|---|---|---|
| An open bet they can see | Join it (`joinBetChance`), picking a side by `pickStyle`, before it locks | Tables with newbie, intermediate or pro |
| At their own pace, or to top up | Propose a sports bet on a live or upcoming game (dev or real) in any mode they like (`modes`): score modes, Either Or, Prop Hunt, King Of The Hill (games with players), Scorcerer (live NFL); or a canned U2Pick | The pro's tables only, so intermediate keeps a quiet history |
| A U2Pick locked 5–30 minutes ago | Call the result, if its settler rule allows them | Tables with a main account |
| Six or more bets waiting for points settlement | The demo host settles the table's points, at most hourly | Tables a demo user hosts |
| Online | Chat (`messagesPerHour`, `gifChance`), react to recent messages (`reactionChance`) | Tables with a main account |
| A bet they were in settles | Trash talk (`trashTalkChance`); others may report or block the talker | Same |
| Someone follows them (after the bots started) | Follow back (`followBackChance`) | Anywhere |
| An invitation | Accept (`inviteAcceptChance`) or decline | Anywhere |
| Now and then | Follow someone (`followsPerDay`): other demo users, sometimes the pro | Anywhere |
| A receipt that's theirs to act on | Mark sent, confirm, dispute, forgive or ask to forgive, per `settleStyle` | Anywhere |

The seeded world stays as seeded: follows that existed before the bots
started never get followed back, and newbie only gets bots if you put him in
a table. The Dev page shows what the bots have done since they started (each
action with its outcome, e.g. `pick updated × 12`). Bot state is in memory, so
a backend restart or a reset starts them over. Turn them off with
`pvpicks.demo.bots.enabled=false`.

## Demo games

`demo/DemoGames.java` keeps three games on the board at all times: two NBA
slots and one NFL slot, staggered. Each slot loops every 100 minutes through
a 15-minute pregame, 75 minutes live and 10 minutes final, and each loop is a
new game (`NBA-DEV-demo-nba1-<loop>`) with its own teams and scores. They go
through the real dev game sync, so score bets on them lock and settle like any
other, and they're marked DEV. A game's state is a pure function of the clock,
so there's nothing to save and a restart picks up mid-game. NBA games end
around 115 a side and NFL around 24, never tied (no overtime in the demo).

Each club has a fixed roster of eight made-up players, and every live or
finished demo game has a box score built from the same minute-by-minute
scoring: points (NBA) or touchdowns, field goals and extra points (NFL, the
kicker carries the kicks) follow the score, and the other
stats grow with the clock. So the player modes and Scorcerer work on demo
games at any hour.

Finished dev games leave the ticker an hour after they end. The scenario files
and recorded chunks on the Dev page still work alongside them (see
[Dev sports data](/dev-sports-data)).

In code: `demo/DemoProfile.java` (parameters, defaults, archetypes),
`demo/DemoRoster.java` (the generated users), `demo/DemoWorld.java` (what a
reset seeds), `demo/DemoHistory.java` (the past), `demo/DemoGames.java`,
`demo/DemoBots.java` (the loop) and `demo/BotMind.java` (their decisions).

## Seeded history

`demo/DemoHistory.java` plays the past out through the real services, then
backdates it: someone at the table proposes (a canned U2Pick, or a score bet
on a made-up past game, `NBA-DEV-history-<n>`), the main account and some
demo users pick, the bet locks, and the host calls it or the game settles it.
Every two weeks each of the pro's tables settles points, and older receipts
are paid, forgiven or left open. Bets, picks, ledger, settlements and receipts
are consistent by construction; only timestamps are rewritten afterwards.
Chat positions and receipt lines are normally immutable, so their guards are
off only inside the backdating transaction. Ledger and settlement rows keep
the time of the reset (nothing shows them).

The main accounts and everyone in their tables have no debt cap, so a losing
streak can't stop the story. One main account's history never includes
another's picks, which keeps intermediate's short. A reset takes about 15
seconds.

## Reset

**Reset data** on the Dev page (the flask icon beside the logo, which works signed out too):

1. pauses the bots;
2. wipes app data: users, sessions, social, tables, bets, chat, ledger,
   settlements, moderation, feedback and the outbox;
3. keeps sports data (real games, players and stats) so the ticker isn't
   empty;
4. reseeds the world in one transaction, then the history through the
   services, and clears the bots' sessions, plans and decisions;
5. signs everyone out, you included, and returns you to the sign-in picker.

The backend also seeds an empty database (no users) on startup. Ledger and
settlement tables are append-only and refuse TRUNCATE; reset turns their
user triggers off only inside its own transaction.

## Not covered

- Bot state is in memory: a backend restart starts the bots over (the world
  and games are unaffected).
