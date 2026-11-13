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

BallDontLie plans as of 2026-11-13 (check before buying):

| Plan | Price | Rate limit | Data |
|---|---|---|---|
| Free | $0 | 5 requests/min | Basic: teams, players, games and scores |
| All-Star | $95.90/year per league | 60 requests/min | Extended: adds player stats |
| GOAT | $383.90/year per league | 600 requests/min | Full: adds live box scores |
| All-Access | $2,879.90/year, all leagues | 600 requests/min | Full, plus webhooks |

Game-result modes work on the free plan. Player-prop and stat modes need at
least All-Star per league, and live in-game stats need GOAT. Which tier the
project needs depends on the first sports modes, which are still being chosen.

## Decision

PostgreSQL is the source of truth for sports data. Files are only for
recordings, replay and test fixtures.

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

Keep raw recordings exactly as received, one file per league per New York day
(implemented: `PVPICKS_SPORTS_RECORDING_DIR`). A small tool slices them into
one timeline per game for replay and fixtures, for example
`fixtures/nba/NBA-18447.jsonl`. Commit only short, trimmed fixtures; keep full
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
Player modes add a paid-plan dependency per league.
