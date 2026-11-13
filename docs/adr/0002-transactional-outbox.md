# ADR 0002: Publish committed events through an outbox

- Status: accepted
- Date: 2026-11-11

## Context

Bet settlement and its ledger entries must not diverge from events consumed by
Quick Picks and other modules.

## Decision

Write domain changes and outbox records in the same PostgreSQL transaction.
A relay publishes them to Kafka after commit. Consumers must deduplicate by
event identity and make their own effects idempotent.

## Consequences

Delivery is at least once, not exactly once. Relay recovery, ordering, consumer
deduplication and failure handling require integration tests. Kafka is not the
source of truth for balances or ledger state.

Flyway V6 provides storage for a stable event ID, aggregate identity, event
type and JSON payload, with an index for unpublished events. Application code
writes it only through `common.Outbox`, inside the caller's transaction, and
every event type and aggregate type is catalogued in `common.OutboxEvent`. This table alone
does not publish events. A future relay must not mark an event published before
broker acknowledgement, and consumers must tolerate duplicate delivery.
Per-aggregate ordering, relay concurrency and retention remain to be designed
and verified before the outbox can provide the intended delivery guarantee.

V14 stores processed-event markers by stable logical consumer and event ID.
Consumers must insert the marker and all database effects for that unit of
work in one transaction, and acknowledge only after commit. If one event fans
out to many recipients, all effects must share that transaction or use a
separately designed per-recipient deduplication scheme; one marker must not
silently hide unfinished work. An external side effect needs its own durable
delivery mechanism. No consumer uses this table yet.

Do not prune markers until replay, dead-letter, backup and retention horizons
are specified and compatible; otherwise old events can repeat their effects.
Event identifiers may be linkable to personal data and need appropriate
access and deletion/retention controls. Different consumer versions or
backfills need deliberately chosen stable identities rather than accidental
reuse of an old consumer name.
