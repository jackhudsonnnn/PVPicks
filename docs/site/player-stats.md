# Player stats

The player modes (Either Or, Prop Hunt, King Of The Hill) resolve on live
per-player game stats, and Scorcerer (NFL and NBA) on team totals of those stats checked against the score. This page
describes where that data comes from, how it's stored, how each mode reads it,
and the edge cases they handle. All of it is implemented.

## Source and plan

BallDontLie serves per-player game stats for both leagues on the **All-Star**
plan ($9.99/month per league), which the project has for the NBA and the NFL
(bought after the GOAT trial, 2026-11). The stats update during games, so they work
for live bets. GOAT is needed only for play-by-play, sportsbook odds and
player props, and advanced stats. Choose Their Fate (how a drive ends) has
an All-Star design for the NFL (below), but it stays "Coming soon" until
turnovers on downs can be detected.
Scorcerer works on All-Star for both leagues from team totals of player
stats (below); NBA ones wash more often, because baskets come fast.

Every endpoint the app calls (games, player stats, active players) is on
All-Star. The LAN stack polls at All-Star rates (`PVPICKS_SPORTS_POLL_INTERVAL=PT15S`
and `PVPICKS_SPORTS_STATS_INTERVAL=PT3S` in `.env.lan`): about 8 score and 20
stats requests a minute, within All-Star's 60 with room for manual checks.
Dev's defaults stay at 5 requests a minute (`PT60S` and `PT20S`), so a dev
stack sharing the key doesn't eat into a party's budget. Set the two
intervals in `infra/compose/.env` to poll faster in dev.

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

1. The live game that has waited longest since its last stats request. For
   the NBA, the next-longest-waiting due NBA game comes too: two games
   (`game_ids[]` twice, at most 36 rows each) fit one 100-row page. NFL games
   reach about 70 rows each, so they're requested one at a time. Two would
   spill onto a second page, which costs a second request. The client follows
   `next_cursor` either way, so no rows are lost.
2. Finished games, every 15 minutes for six hours after their start, so late
   stat corrections land.
3. Otherwise, the next page of a league's active-player list, when a
   refresh is due or in progress. While games are live and a refresh is due,
   every fourth request goes to it instead, so a game day can't starve it.
   At dev's 5 requests a minute that's a full refresh about every half hour
   during games (and live stats about a quarter slower); at All-Star rates it
   takes a few minutes.

A rate-limited response pauses polling for a minute. Scores and player stats
share the plan's request budget. At dev's 5 requests a minute, that's two for
scores and three for player stats, so with five NBA games live each
one's stats refresh about once a minute. With All-Star (60 a minute), the
interval can drop to every few seconds.

## Season averages

Owner decision, 2026-11-24. Before kickoff every player is on 0, so the
player picker ranks ties by each player's per-game average this season and
starts a Prop Hunt line (or King Of The Hill target) at it, never below what
they've already done. When the poller has nothing else to do (no live games,
no active-player refresh due), one request fetches up to 100 players on teams
with a game in the next day whose averages are missing or a day old:
`/nfl/v1/season_stats` (All-Star; totals divided by games played) or
`/nba/v1/season_averages/general` (it may need a higher plan: a 401 or 403
switches NBA averages off until restart, and the picker ranks by position
alone). They're stored in `player_season_averages` in the modes' stat
keys and returned as `averages` on `GET /games/{id}/players`. Players the
provider has nothing for are marked checked, so they aren't asked for again
that day. Dev games have no averages. In code: `sports/SeasonAverage`,
`StatsPoller.refreshAverages`.

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
| NFL | `field_goals`, `extra_points` | Not offered; Scorcerer's team totals | `field_goals_made`, `extra_points_made` |
| NBA | `twos`, `free_throws` | Not offered; Scorcerer's team totals | `fgm` minus `fg3m`, `ftm` |
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
proposal sheet lists the game's players with their position and current
number, sorted by position, then team and name (`GET /games/{id}/players`:
both teams' active players plus anyone in the game's stats; the order is
`sports/roster-order.ts` in the client). The server builds the question and choices from the stored
players; two players with the same name are told apart by team.

| Mode | Choices | Settles | Washes |
|---|---|---|---|
| Either Or | The two players | After Final plus the 20-minute grace: whoever has more | Tie; a player didn't play |
| Prop Hunt | Over / Under the line | After Final plus the grace | The line must start above the player's number, and passing it before lock washes; push on a whole line; didn't play |
| King Of The Hill | The two players, then Neither | As soon as someone reaches the target (mid-game); Neither after Final plus the grace | The target must start above both; reaching it before lock washes; both reaching it in the same update; didn't play (for Neither) |
| Scorcerer | Away big, away small, home big, home small (NFL touchdown and field goal, NBA 3 and 2), No more scores | When the first such score after lock shows in the team totals (and they explain the score); No more scores after Final plus the grace | Two different scores first seen together; stats behind the score at lock while the score moves again; totals that still don't explain the score after the grace (see below) |

**Overtime counts** off: values are the last observed before the game was
first seen in overtime, and a target reached in overtime doesn't count.

Code: `bets/PlayerOutcome`, `bets/ScorcererOutcome`, `bets/PlayerLockRules`,
`sports/PlayerStatsLookup`.

## Scorcerer from team totals

Implemented (owner decisions, 2026-11-15; teams and the NBA added 2026-11-21).
Score jumps alone can't tell some plays apart. In the NFL, a safety, a
two-point conversion and a defensive two-point return all add 2; in the NBA,
two free throws add 2 like a basket. Two scores between updates are
unreadable either way. So Scorcerer reads **what** scored, and for which
team, from team totals of player stats, and uses the score to check them.

- **Choices**, away team first:
  - NFL: `<away> touchdown`, `<away> field goal`, `<home> touchdown`,
    `<home> field goal`, No more scores. Safeties and every other 2-point play
    don't count.
  - NBA: `<away> 3`, `<away> 2`, `<home> 3`, `<home> 2`, No more scores. Free
    throws don't count.
- **Team totals.** For each team in the game, sum over its players' lines:
  - NFL: `touchdowns` (every type), `field_goals` (`field_goals_made`) and
    `extra_points` (`extra_points_made`);
  - NBA: `threes` (`fg3m`), `twos` (`fgm` minus `fg3m`) and `free_throws`
    (`ftm`).

  The scoring keys that aren't offered as bet stats are canonical but hidden
  (`StatLine.SCORING`). A player without a line counts as 0.
  `PlayerStatsLookup.scoringTotals` replays `player_stat_snapshots` to give
  the totals after every observation.
- **Explaining the score.**
  - NFL: the totals explain a team's score when the score minus 6 per
    touchdown, 3 per field goal and 1 per extra point is an even number from
    0 to 4. That leaves room for up to two 2-point plays, which the feed
    doesn't report.
  - NBA: 3, 2 and 1 per free throw must add up to the score exactly.
- **Baseline.** The totals at lock must explain the score at lock. If the
  stats were behind, later observations may catch up while the score stands
  still. If the score moves again first, the bet washes.
- **Next score.** The first observation after the baseline where any of the
  four counts rises (either team's big or small score) decides it, once the
  latest totals explain the latest score (until then it waits). Everything
  else is skipped: extra points, two-point conversions, defensive returns,
  safeties, free throws, and an NFL try that lands after lock for a
  touchdown before it. Nothing by Final means No more scores, after the grace
  period.
- **Wash.** "Couldn't tell the scoring play" when two different counts first
  rise in the same observation, since their order is unknown. Two of the same
  kind for one team still decide it. It also washes when the totals still
  don't explain the score once the grace period has passed. Hosts can
  override.
  - NBA teams score about every 15 seconds, so two baskets often land between
    stats updates, and NBA Scorcerer washes far more often than the NFL's.
    The owner accepted that (2026-11-21). Short pick windows on live games
    help.

**Evidence.** In the 2026-11-15 NFL recordings (12 games), each player's
latest row gives team totals that match the score exactly for 20 of 24
teams. Three teams are off by exactly 2: two-point conversions or safeties,
which the feed doesn't report and Scorcerer skips. One team shows one
touchdown more than its score, likely a stale row after a stat correction.
That case is what the disagreement wash is for.

## Choose Their Fate from team totals (design, blocked)

Not scheduled: after LAN, and blocked on one open problem. NFL only; NBA
possessions (about 14 seconds each) end faster than stats update. Decided
2026-11-15. The design uses the same team totals as
[Scorcerer](#scorcerer-from-team-totals).

- **Question.** How does the drive in progress when picks lock end?
- **Choices.** Touchdown, Field goal, Punt, Turnover.
- **Deciding.** The first drive-ending total to rise after lock, for either
  team:

  | Total rises | Outcome |
  |---|---|
  | Touchdowns (every type) | Touchdown, unless a turnover rose in the same update: a pick-six or fumble return is Turnover |
  | Field goals made | Field goal |
  | `punts` | Punt |
  | Interceptions thrown (`passing_interceptions`), `fumbles_lost`, missed field goals (attempts minus makes), or a safety (a 2-point rise not explained as a try) | Turnover |

- **Washes.**
  - The half, regulation or the game ends before the drive does.
  - The drive ends before picks lock ("Drive ended before picks locked").
    Stats lag, so an ending first seen within one stats-poll interval after
    lock also washes.
  - Two drive endings appear in the same update, except the touchdown and
    turnover pair above.

**Blocker: turnover on downs.** It never appears in the feed, so the bet
would wrongly settle on the next drive's ending. One possible workaround:
possession passes to the opponent of the team whose drive just ended, so an
ending credited to the team that shouldn't have the ball means the drive
changed hands silently. It can't work for the first drive of a half, when
the kickoff winner is unknown. Onside kicks also break the pattern. The owner
decided to keep the mode "Coming soon" until this is solved.

**Evidence.** In the 2026-11-15 NFL recordings (12 games), every team's
drive endings add up to about 11 a game, the normal NFL count. Only 13 of
220 updates with a drive ending held two or more, even at the trial's
refresh of about every 2 minutes per game.

## Testing player behavior

Dev scenario files and recorded chunks feed made-up or replayed games through
the same pipeline as live data, so you can check how a mode resolves without
waiting for a real game. The recorded chunks include full NBA and NFL games
with live player stats. See [Dev sports data](/dev-sports-data). The demo's
always-live games also have made-up box scores ([Demo world](/demo-data)).
