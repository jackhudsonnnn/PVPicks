# ADR 0003: Keep Redis disposable

- Status: accepted
- Date: 2026-11-11

## Context

Presence, snapshots, rate limiting and realtime fan-out benefit from Redis,
but a cache failure must not corrupt betting or settlement.

## Decision

PostgreSQL is the source of truth; Redis is not authoritative for persistent
business state. Cached data can be invalidated and rebuilt. Correctness of
mutation idempotency must not depend solely on Redis.

## Consequences

Design explicit behavior for Redis outages, including authentication, rate
limits and realtime delivery; do not assume all features can continue unchanged.
Test that Redis loss cannot produce duplicate or inconsistent ledger effects.

The HTTP duplicate-prevention window is at least 24 hours. Implementations must keep the
authoritative idempotency record in PostgreSQL for at least 24 hours after
commit, atomically with the mutation, even when Redis is unavailable. Redis
may cache a response but cannot be the sole record. Retention and deletion
must account for personal data and avoid recording plaintext picks, notes or
other request bodies in logs or fingerprints.
