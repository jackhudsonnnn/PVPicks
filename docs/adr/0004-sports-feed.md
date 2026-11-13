# ADR 0004: Abstract sports data behind a canonical feed

- Status: accepted
- Date: 2026-11-11

## Context

Game bets must resolve from a feed, while automated tests must be deterministic.

## Decision

Use a `GameFeed` interface and one versioned canonical event schema.
BallDontLie is the runtime provider in dev, LAN and production; a scripted feed
is available only in automated tests. Keep credentials outside source control.

## Consequences

Contract-test both implementations against the canonical schema. Poll within
provider limits with backoff and observable freshness. Verify NBA/NFL coverage,
required statistics, event chronology and access tier before relying on them
for a live event. Do not automatically settle order-dependent modes when the
available feed cannot establish their outcomes.

## Implementation

`sports.GameFeed` returns canonical `FeedGame` snapshots. `BallDontLieFeed`
follows the provider docs (https://docs.balldontlie.io for NBA,
https://nfl.balldontlie.io for NFL) and reads `PVPICKS_BALLDONTLIE_API_KEY`;
without a key nothing is polled and the ticker reports itself unavailable.
Every `PVPICKS_SPORTS_POLL_INTERVAL` (default 60 seconds) the poller makes one
request per league for yesterday, today and tomorrow in America/New_York, so
late games are found whether the provider dates them by UTC or local time, and
places games by their own start time. That is two requests a minute, within the
free tier's five. A failing league backs off exponentially, at least a minute
when rate limited and at most ten minutes. Snapshots and per-league feed health
are stored in `games` and `sports_feed_status` (V27).

The lifecycle comes from the provider's `status_state`; `delayed` counts as
scheduled, `canceled`, `suspended` and `abandoned` as postponed, and `unknown`
games are skipped. The free-text `status` is only a fallback and the live
label. NBA games use `datetime`, `period` and `time`. NFL responses have no
clock or quarter field, so the period is the latest quarter with a score
(overtime is period 5) and the label stands in for the clock; this still needs
checking against live NFL games. Games without a readable start time or teams
are skipped. Scores are not yet used to resolve bets.

When `PVPICKS_SPORTS_RECORDING_DIR` is set, every raw response is appended to
`<league>-<New York date>.jsonl` there (Compose mounts the git-ignored
`infra/compose/recordings/`). Recordings omit the API key. They are meant for
debugging and for the dev replay feed proposed in ADR 0005, which would amend
this decision.
