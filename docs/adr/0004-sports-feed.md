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
