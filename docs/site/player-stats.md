# Player stats

The player modes (Either Or, Prop Hunt, King Of The Hill) resolve on live
per-player game stats. This page describes where those stats come from, how
they're stored, and the edge cases a mode must handle. Implemented: polling,
storage and history. Not yet implemented: the player modes themselves.

## Source and plan

BallDontLie serves per-player game stats for both leagues on the **All-Star**
plan ($9.99/month per league). The stats update during games, so they work
for live bets. GOAT is needed only for play-by-play, sportsbook odds and
player props, and advanced stats. Choose Their Fate and Scorcerer need
play-by-play, so they stay "Coming soon" (ADR 0005).

| League | Endpoint | Rows |
|---|---|---|
| NBA | `GET /v1/stats?game_ids[]=…&per_page=100` | Every player on both rosters, including ones who didn't play (`min` blank or `"00"`). |
| NFL | `GET /nfl/v1/stats?game_ids[]=…&per_page=100` | Only players who recorded something; stats a player didn't record are `null`. |

Active players come from `GET /v1/players/active` and
`GET /nfl/v1/players/active`. They're refreshed at most once a day, and
players seen in a game are added too.

## Polling

One request every `PVPICKS_SPORTS_STATS_INTERVAL` (default 20 s), chosen in
this order:

1. The live game that has waited longest since its last stats request.
2. Finished games, every 15 minutes for six hours after their start, so late
   stat corrections land.
3. Otherwise, the next page of a league's active-player list (daily).

A rate-limited response pauses polling for a minute. Scores and player stats
share the plan's request budget. With the 5-requests-a-minute trial, that's
two for scores and three for player stats. With All-Star (60 a minute), the
interval can drop to every few seconds.

## Canonical stats

Modes read only these keys, never provider fields. The provider-to-canonical
mapping is in `sports/StatLine.java`, and each new provider maps into the
same keys.

| League | Key | Label | From the provider |
|---|---|---|---|
| NBA | `points` | Points | `pts` |
| NBA | `rebounds` | Rebounds | `reb` |
| NBA | `assists` | Assists | `ast` |
| NBA | `threes` | 3-Pointers Made | `fg3m` |
| NBA | `stocks` | Steals + Blocks | `stl` + `blk` |
| NFL | `passing_yards` | Passing Yards | `passing_yards` |
| NFL | `rushing_yards` | Rushing Yards | `rushing_yards` |
| NFL | `receiving_yards` | Receiving Yards | `receiving_yards` |
| NFL | `receptions` | Receptions | `receptions` |
| NFL | `touchdowns` | Touchdowns | `rushing_touchdowns` + `receiving_touchdowns` |

Rules every mode can rely on:

- **Whole numbers.** Missing or `null` counts as 0. Yards can be negative
  (a loss).
- **Played.** An NBA player played if their minutes include any non-zero
  digit. An NFL player played if the provider lists a row for them (it lists
  only players who did something). A player-prop bet on someone who didn't
  play washes.
- **Touchdowns are the player's own scores:** rushing plus receiving.
  Passing touchdowns count for the receiver, not the passer. Return,
  fumble-recovery and defensive touchdowns (`kick_return_touchdowns`,
  `punt_return_touchdowns`, `fumbles_touchdowns`, `interception_touchdowns`)
  are **not** counted yet. Decide this before building a "did X score a TD"
  prop.
- **Whole game.** Totals cover the whole game. Whether overtime counts is the
  proposer's choice per bet. The stats endpoint doesn't split overtime out,
  so player-prop bets that exclude overtime need a cutoff snapshot (see
  history below).

## Storage

| Table | What it holds |
|---|---|
| `players` | One row per league player: `id` is `NBA-<provider id>` or `NFL-<provider id>`, plus name, position, team and whether they're on the active list. |
| `player_game_stats` | Each player's current whole-game line per game: canonical `stats` (JSON), `played`, `observed_at`. |
| `player_stat_snapshots` | One row per observed change to a line, never updated or deleted. King Of The Hill reads it to find who crossed a target first; an overtime cutoff reads the last snapshot before overtime began. |
| `sports_feed_status.players_refreshed_at` | When each league's active list was last fully refreshed. |

Raw responses are also saved as files, in development only, under
`recordings/sports/<league>/<date>/`. They're used for replay and test
fixtures, and are never read by the running app.

## Live updates and corrections

- Lines change during the game; each change writes a snapshot.
- Lines can also change **after Final**. The provider corrects stats for a
  while, so finished games are re-read every 15 minutes for six hours. Score
  bets wait 20 minutes after Final before settling, and player modes should do
  the same. A correction after a bet settled doesn't change it; a host can
  override if needed.
- A player missing from a later response keeps their last line. Rows aren't
  deleted.

## Samples

Saved responses live in `recordings/sports/samples/` (git-ignored, so on your
machine only). Trimmed fixtures are checked against the provider's OpenAPI
specs in `backend/app/src/test/resources/fixtures/balldontlie/`
(`nba-stats.json`, `nfl-stats.json`).

An NBA row, trimmed:

```json
{ "min": "25:52", "pts": 14, "reb": 1, "ast": 5, "fg3m": 3, "stl": 0, "blk": 1,
  "player": { "id": …, "first_name": "…", "last_name": "…" },
  "team": { "abbreviation": "…" }, "game": { "id": 21718092 } }
```

It maps to `{"points": 14, "rebounds": 1, "assists": 5, "threes": 3, "stocks": 1}`,
with `played: true`.

An NFL row, trimmed. The provider sends `null` for anything not recorded:

```json
{ "rushing_yards": null, "receiving_yards": 54, "receptions": 4,
  "rushing_touchdowns": null, "receiving_touchdowns": 1,
  "player": { "id": 13878820, "first_name": "Kain", "last_name": "…" },
  "game": { "id": 1392352 } }
```

It maps to `receiving_yards: 54`, `receptions: 4` and `touchdowns: 1`, with
everything else 0.

## Testing player behavior

Dev scenario files and recorded chunks feed made-up or replayed games through
the same pipeline as live data, so you can check how a mode resolves without
waiting for a real game. See [Dev sports data](/dev-sports-data).
