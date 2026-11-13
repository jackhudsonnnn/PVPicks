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
every event type and aggregate type is catalogued in `common.OutboxEvent`. The
`delivery.OutboxRelay` publishes rows to the Kafka topic `p2picks.outbox.v1`,
keyed by aggregate, and marks them published only after the broker
acknowledges the batch; a failed batch is retried. Relays share the table with
`FOR UPDATE SKIP LOCKED`. Events are published in `created_at` order, which can
differ from commit order, so consumers must not rely on ordering and must
tolerate duplicates. A daily job deletes published rows older than seven days
(`PVPICKS_OUTBOX_RETENTION`); unpublished rows are never deleted. Kafka, not
the outbox, is the replayable stream.

V14 stores processed-event markers by stable logical consumer and event ID.
Consumers must insert the marker and all database effects for that unit of
work in one transaction, and acknowledge only after commit. If one event fans
out to many recipients, all effects must share that transaction or use a
separately designed per-recipient deduplication scheme; one marker must not
silently hide unfinished work. An external side effect needs its own durable
delivery mechanism. No consumer uses this table yet: the realtime consumer's
invalidations are idempotent and best effort, so it records no markers.

Do not prune markers until replay, dead-letter, backup and retention horizons
are specified and compatible; otherwise old events can repeat their effects.
Event identifiers may be linkable to personal data and need appropriate
access and deletion/retention controls. Different consumer versions or
backfills need deliberately chosen stable identities rather than accidental
reuse of an old consumer name.
