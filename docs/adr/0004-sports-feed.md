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
reads `PVPICKS_BALLDONTLIE_API_KEY`; without a key nothing is polled and the
ticker reports itself unavailable. The poller covers NBA and NFL games on the
current America/New_York date, plus the previous date while any of its games
are unfinished, every `PVPICKS_SPORTS_POLL_INTERVAL` (default 60 seconds, two
or three requests per poll). A failing league backs off exponentially, at least
a minute when rate limited and at most ten minutes. Snapshots and per-league
feed health are stored in `games` and `sports_feed_status` (V27).

The NBA mapping uses `datetime`, `status`, `period` and `time`. The NFL mapping
uses `date` and the status label, with `quarter`/`time` when present; it has
not yet been checked against live NFL responses. Status labels that are not
clearly live or over are treated as scheduled, and games without a readable
start time or teams are skipped. Scores and statistics are not yet used to
resolve bets; that requires the verification above.
