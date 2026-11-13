# Realtime contract

Realtime delivery is implemented for table bet feeds, table chat feeds and a
private settlement feed. The transport is STOMP over a WebSocket at `/ws`
(`/api/ws` through the Vite dev proxy). Messages are invalidations only: HTTP
reads remain authoritative and provide recovery when realtime delivery is
interrupted. The ticker feed is planned and not implemented.

## Connection and authorization

- The WebSocket handshake goes through the normal HTTP security chain: it
  needs an active player's HttpOnly session cookie and must come from the
  allowed `Origin` (`PVPICKS_DEV_ORIGIN`). It must also carry the CSRF cookie;
  the STOMP `CONNECT` frame must carry the matching token from `/auth/csrf` in
  its `X-CSRF-TOKEN` header. Credentials and tokens never go in a URL. An
  authenticated handshake alone does not authorize a subscription.
- Expiry, logout, account freeze or deletion ends access: every delivery
  rechecks the login session in PostgreSQL, and a sweep every 15 seconds closes
  connections whose session is no longer valid. Revocation does not depend on
  Redis.
- Every subscription is authorized when it is made, and every delivery
  rechecks it. A table feed is restricted to current members of the
  non-archived table; the settlement feed is the caller's own. There are no
  shared broadcast topics: each message is sent to one connection after the
  checks, so a removed member stops receiving immediately.
- Never rely on the client to hide data. A table message cannot contain an
  Active bet's individual picks or a caller-specific `myPick`; the authorized
  HTTP bet view supplies the requesting user's own pick. Block filtering is
  applied by the HTTP chat window for each recipient.
- Clients cannot send: any STOMP `SEND` frame is rejected, as is a
  subscription to any other destination. A rejected frame closes the
  connection with a STOMP `ERROR`.

## Destinations

Clients subscribe to user destinations; the server routes each message to the
subscribing connection only.

| Subscribe to | Audience | Payload | Source outbox events |
|---|---|---|---|
| `/user/queue/tables/{id}/bets` | Current table members | `bet.changed` with the bet ID, or `bets.changed` for the whole list | `bet.created`, `bet.pick_count.changed`, `bet.locked`, `bet.resolved`; `settlement.created` (list) |
| `/user/queue/tables/{id}/chat` | Current table members | `chat.changed`, without a message identity; fetch the filtered HTTP window | `chat.message.created`, `chat.reaction.changed` |
| `/user/queue/settlements` | The two parties to a receipt or net | `settlements.changed`, with no IDs or amounts; refetch reviews, groups and history | `settlement.created` (each party), `settlement.line.changed`, `settlement.group.changed`, `settlement.net.changed` |
| `/topic/ticker` (planned) | Authenticated users | `ticker.changed`; refetch `GET /ticker` | Sports feed, not implemented |

Other outbox events have no realtime destination yet. They are not delivered
to clients, and the notes below still apply to any future delivery.

### Events without a destination yet

These table, membership and profile events reach Kafka but are not delivered to
clients. Each needs its own audience and privacy review first:

- `table.member.permission.changed.v1` is not a shared-table event: only current
  hosts and the affected member may learn the change, so it needs a private
  invalidation.
- `table.remainder.changed.v1`, `table.host.changed.v1`,
  `table.settings.changed.v1` and `table.profile.changed.v1` need a table-view
  invalidation destination. Their system chat messages already reach the chat
  feed. Table-profile changes also affect pending invitation views, which must
  be invalidated privately for currently eligible recipients; invitees must not
  gain access to table feeds.
- Quick Pick control events contain only the affected table ID; delivery must
  recheck membership and must not expose the source's code or cross-table
  relationships.
- `table.member.removed.v1` contains the removed user ID. Delivery rechecks
  already stop that user's table feeds; a private table-list invalidation is
  still needed. Never broadcast the raw event.
- `table.archived.v1` should end table subscriptions and privately invalidate
  eligible archive lists.
- `user.profile.changed.v1` contains only a user ID; profile invalidations need
  recipient-specific authorization and block filtering.

## Invalidation envelope

The versioned schema is `../../backend/api/realtime.schema.json`. Destinations
accept only the corresponding event type in the table above. A chat event has
no message ID: a shared topic cannot safely contain a recipient-specific
blocked-message view. A table member may observe that the table changed, but
message content and identity must come only from the filtered HTTP response.
Ticker invalidations contain no table or resource ID.
The sports-only ticker invalidates on changes to its game snapshot; bet
lifecycle changes do not add bet activity to this shared topic.

```json
{
  "eventId": "a9b04155-1c73-4c69-b020-000000000001",
  "type": "bet.changed",
  "schemaVersion": 1,
  "occurredAt": "2026-11-11T12:00:00Z",
  "tableId": "a9b04155-1c73-4c69-b020-000000000002",
  "resourceId": "a9b04155-1c73-4c69-b020-000000000003"
}
```

`eventId` identifies the emitted invalidation for deduplication. It is
derived from the outbox event ID and the invalidation type, so a redelivered
event keeps its ID. `resourceId` is the affected bet for `bet.changed` only.
`bets.changed` names only the table; `settlements.changed` carries no table or
resource ID. The envelope contains no pick, message
identity or text, username, credential or financial amount. The schema and its
tests define the contract the gateway sends.

## Delivery and recovery

- The outbox relay polls unpublished rows every 500 ms, oldest first, publishes
  them to the Kafka topic `p2picks.outbox.v1` keyed by aggregate, and marks them
  published only after the broker acknowledges the batch. A failed batch rolls
  back and is retried, so delivery is at least once. `FOR UPDATE SKIP LOCKED`
  lets several relays share the outbox. Ordering follows `created_at`, not
  commit order.
- Each backend instance consumes every event with its own consumer group,
  starting from the latest offset, and serves only its own connections.
  Invalidations are idempotent, so this consumer records no processed-event
  markers. Malformed or undeliverable events are logged and skipped.
- Realtime invalidations are best effort and may be delayed, duplicated or
  missed. Clients drop repeated event IDs, tolerate reordered invalidations and
  refetch current HTTP state rather than treating event order as authoritative.
- After connect, reconnect, session renewal, membership change or a failed
  refresh, clients subscribe and then refetch relevant authorized resources
  and reconcile them with local state. An invalidation received during a
  refetch must schedule another refresh so a late response cannot hide a
  change. Dev chat history uses opaque before/after cursors and an around
  message ID. Retain the last nonempty window's next cursor to fetch arrivals
  even after reaching the current end; an empty page has no replacement cursor.
  Refetch existing windows after block changes: a newly visible message can be
  older than the saved next cursor.
- PostgreSQL remains authoritative; Kafka and the gateway are not proof that a
  client received an event. Redis is not used for delivery.
- Ordering, replay and gap detection beyond refetch-on-reconnect remain open
  design work. Do not promise exactly-once delivery.

## Verification targets

`delivery.RealtimeIntegrationTest` covers targets 1–5 for the implemented
feeds; the ticker targets (6–7) await the sports feed.

1. A non-member and a removed member cannot subscribe to a table topic.
2. No shared Active-bet message reveals individual picks or another user's
   private view; HTTP returns only the caller's own pick.
3. Blocked chat content is not delivered to the blocking user.
4. Duplicate, delayed and reordered invalidations do not overwrite newer
   server state; reconnect refetches missed changes.
5. Session expiry or revocation terminates or denies further access.
6. The ticker includes today's games and an earlier-date game still live after
   midnight, deduplicates games and applies the specified order. Test Eastern
   calendar dates across both daylight-saving transitions.
7. An unavailable feed is not represented as a successful empty ticker, and no
   bet, table, participant or financial fields appear in ticker snapshots.
