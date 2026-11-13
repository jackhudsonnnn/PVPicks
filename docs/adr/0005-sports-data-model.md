# ADR 0005: Sports data model, ingestion and replay

- Status: proposed (not yet implemented)
- Date: 2026-11-13

## Context

Sports bets resolve from feed data, and many edge cases depend on exactly what
the feed reported and when: cancellations, delays, suspensions, ties, late
corrections and players who did not play. The current `sports` module (ADR 0004)
stores only the latest snapshot of each game. It needs history for resolution
and audit, roster and player-stat data for player modes, and a way to develop
and test against real game data without the live API.

BallDontLie plans as of 2026-11-13, per league unless noted (account-tier tables at
https://nba.balldontlie.io and https://nfl.balldontlie.io; check before buying):

| Plan | Price | Rate limit | Adds |
|---|---|---|---|
| Free | $0 | 5 requests/min | Teams, players, games and scores |
| All-Star | $9.99/month or $95.90/year | 60 requests/min | Player game stats (real time during games), active players, injuries; NFL also team and season stats, standings and snap counts |
| GOAT | $39.99/month or $383.90/year | 600 requests/min | Box scores, advanced stats, play-by-play, betting odds, sportsbook player props, lineups; NFL team rosters |
| All-Access | $2,879.90/year, all leagues | 600 requests/min | Everything, plus webhooks |

The GOAT 7-day trial is limited to 5 requests a minute.

The user plans to start on All-Star and upgrade as the app grows. All-Star
covers game results and player props, including live prop progress during a
game, and injuries for proposal warnings. GOAT is the upgrade for sportsbook
lines and odds, play-by-play bet types and higher request rates. On All-Star,
NFL rosters must be derived from players' team fields, because the NFL Team
Roster endpoint is GOAT-only; this needs checking against real responses.

## Decision

PostgreSQL is the source of truth for sports data. Files are only for
recordings, replay and test fixtures.

Treat the provider plan as a per-league setting (for example
`PVPICKS_BALLDONTLIE_PLAN_NBA=ALL_STAR`). The plan decides the request budget
and poll interval, which endpoints are called, and which bet modes are offered,
so upgrading a league is a configuration change.

Keep three datasets apart, because they change at different rates and come
from different endpoints:

- **Teams and rosters:** players and team membership, refreshed about daily.
- **Games:** schedule, status and score, polled while relevant (implemented).
- **Player game stats:** per-player box scores, fetched only for modes that
  need them and only on a plan that includes them.

Store an append-only history of game snapshots, one row per observed change,
next to the current snapshot. Resolution reads the history, so it can tell
when a game went final, whether a correction followed and what the feed
showed when a bet locked. The history also serves disputes and replay.

Keep raw recordings exactly as received, one timestamped file per response,
grouped by league and New York day (implemented: `PVPICKS_SPORTS_RECORDING_DIR`,
`recordings/sports/<league>/<date>/<UTC timestamp>_<endpoint>.json`). Names sort
in receive order, so replay reads them in name order. A small tool slices them
into one timeline per game for fixtures, for example `fixtures/nba/NBA-18447.jsonl`. Commit only short, trimmed fixtures; keep full
recordings out of git for size and because the provider's terms on storing
its data have not been checked.

Add a replay feed: an opt-in `GameFeed` for the dev profile that plays back
recorded games, optionally sped up. Everything after the feed (snapshots,
history, outbox, realtime, ticker and bet resolution) then runs the real code.
This amends ADR 0004, which allows a scripted feed only in automated tests.
Once replay exists, the prototype's dev-only Simulate menu is retired.

## Consequences

Edge-case rules (domain rules, "Sports outcomes and host overrides") can be
tested against real recorded games instead of guesses. Replay only reproduces
what was polled: at one poll a minute it misses changes in between, which is
fine for status and score lifecycles but not for order-dependent play-by-play
modes. Those need a richer source before they can settle automatically.
Player modes add a paid-plan dependency per league, starting with All-Star.
