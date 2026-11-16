# Player stats

The player modes (Either Or, Prop Hunt, King Of The Hill) resolve on live
per-player game stats, and Scorcerer (NFL) on the score history. This page
describes where that data comes from, how it's stored, how each mode reads it,
and the edge cases they handle. All of it is implemented.

## Source and plan

BallDontLie serves per-player game stats for both leagues on the **All-Star**
plan ($9.99/month per league). The stats update during games, so they work
for live bets. GOAT is needed only for play-by-play, sportsbook odds and
player props, and advanced stats. Choose Their Fate (how a drive or
possession ends) needs play-by-play, so it stays "Coming soon" (ADR 0005).
Scorcerer works on All-Star for the NFL by reading score jumps (below); NBA
baskets come too fast for that.

Every endpoint the app calls (games, player stats, active players) is on
All-Star, so switching from the trial is configuration only:

1. If BallDontLie issues a new key, replace `PVPICKS_BALLDONTLIE_API_KEY` in
   `infra/compose/.env`.
2. Add `PVPICKS_SPORTS_POLL_INTERVAL=PT15S` and
   `PVPICKS_SPORTS_STATS_INTERVAL=PT3S` there. That's about 8 score and 20
   stats requests a minute, within All-Star's 60 with room for manual checks.
3. Redeploy the backend.

| League | Endpoint | Rows |
|---|---|---|
| NBA | `GET /v1/stats?game_ids[]=…&per_page=100` | Every player on both rosters, including ones who didn't play (`min` blank or `"00"`). |
| NFL | `GET /nfl/v1/stats?game_ids[]=…&per_page=100` | Only players who recorded something; stats a player didn't record are `null`. |

Active players come from `GET /v1/players/active` and
`GET /nfl/v1/players/active`. Each league's list is re-fetched every
`PVPICKS_SPORTS_PLAYERS_INTERVAL` (default 10 minutes), and players seen in a
game are added too. Every page is recorded like any other response. A full
refresh is about 25 requests (NBA about 5 pages, NFL about 20).

## Polling

One request every `PVPICKS_SPORTS_STATS_INTERVAL` (default 20 s), chosen in
this order:

1. The live game that has waited longest since its last stats request,
   together with the next-longest-waiting due game in the same league: one
   request covers two games (`game_ids[]` twice), which fits one 100-row page
   in both leagues.
2. Finished games, every 15 minutes for six hours after their start, so late
   stat corrections land.
3. Otherwise, the next page of a league's active-player list, when a
   refresh is due or in progress. While games are live and a refresh is due,
   every fourth request goes to it instead, so a game day can't starve it.
   On the trial's 5 requests a minute that's a full refresh about every half
   hour during games (and live stats about a quarter slower); on All-Star it
   takes a few minutes.

A rate-limited response pauses polling for a minute. Scores and player stats
share the plan's request budget. With the 5-requests-a-minute trial, that's
two for scores and three for player stats, so with five NBA games live each
one's stats refresh about once a minute. With All-Star (60 a minute), the
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
| NFL | `touchdowns` | Touchdowns | `rushing_touchdowns` + `receiving_touchdowns` + `kick_return_touchdowns` + `punt_return_touchdowns` + `fumbles_touchdowns` + `interception_touchdowns` |

Rules every mode can rely on:

- **Whole numbers.** Missing or `null` counts as 0. Yards can be negative
  (a loss).
- **Played.** An NBA player played if their minutes include any non-zero
  digit. An NFL player played if the provider lists a row for them (it lists
  only players who did something). A player-prop bet on someone who didn't
  play washes.
- **Touchdowns are every touchdown the player scored** (owner decision,
  2026-11-15): rushing, receiving, kick and punt returns, fumble recoveries and
  interceptions. Passing touchdowns count for the receiver, not the passer.
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

Saved responses live in `dev/sports/samples/` (git-ignored, so on your
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

## How the modes read it

Both players in Either Or and King Of The Hill come from the bet's game. The
proposal sheet lists the game's players with their current numbers
(`GET /games/{id}/players`: both teams' active players plus anyone in the
game's stats). The server builds the question and choices from the stored
players; two players with the same name are told apart by team.

| Mode | Choices | Settles | Washes |
|---|---|---|---|
| Either Or | The two players | After Final plus the 20-minute grace: whoever has more | Tie; a player didn't play |
| Prop Hunt | Over / Under the line | After Final plus the grace | The line must start above the player's number, and passing it before lock washes; push on a whole line; didn't play |
| King Of The Hill | The two players, then Neither | As soon as someone reaches the target (mid-game); Neither after Final plus the grace | The target must start above both; reaching it before lock washes; both reaching it in the same update; didn't play (for Neither) |
| Scorcerer (NFL) | Touchdown, Field goal, Safety, No more scoring | When the first score after lock is seen; No more scoring after Final plus the grace | Two scores in one update, or a jump that isn't one scoring play |

**Overtime counts** off: values are the last observed before the game was
first seen in overtime, and a target reached in overtime doesn't count.

**Scorcerer** compares each stored score update with the one before. One team
rising by 6–8 is a touchdown (with its try), 3 a field goal, 2 a safety. An
extra point or two-point try that arrives after lock for a touchdown scored
before it (within 5 minutes, same team) isn't the next score. Code:
`bets/PlayerOutcome`, `bets/ScorcererOutcome`, `bets/PlayerLockRules`,
`sports/PlayerStatsLookup`.

## Testing player behavior

Dev scenario files and recorded chunks feed made-up or replayed games through
the same pipeline as live data, so you can check how a mode resolves without
waiting for a real game. The recorded chunks include full NBA and NFL games
with live player stats. See [Dev sports data](/dev-sports-data). The demo's
always-live games also have made-up box scores ([Demo world](/demo-data)).
