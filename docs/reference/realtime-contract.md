# Realtime contract

Realtime delivery is implemented for every outbox event: table view, bet and
chat feeds, a private settlement feed and a personal feed. The transport is STOMP over a WebSocket at `/ws`
(`/api/ws` through the Vite dev proxy). Messages are invalidations only: HTTP
reads remain authoritative and provide recovery when realtime delivery is
interrupted.

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
  non-archived table; the settlement and personal feeds are the caller's own. There are no
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
| `/user/queue/tables/{id}` | Current table members; hosts and the affected member only for permission changes | `table.changed`; refetch the table | `table.profile.changed`, `table.settings.changed`, `table.remainder.changed`, `table.host.changed`, `table.quickpicks.changed`, `table.member.joined`, `table.member.removed`, `table.member.permission.changed` |
| `/user/queue/tables/{id}/bets` | Current table members | `bet.changed` with the bet ID, or `bets.changed` for the whole list | `bet.created`, `bet.pick_count.changed`, `bet.locked`, `bet.resolved`; `settlement.created` (list) |
| `/user/queue/tables/{id}/chat` | Current table members | `chat.changed`, without a message identity; fetch the filtered HTTP window | `chat.message.created`, `chat.reaction.changed` |
| `/user/queue/settlements` | The two parties to a receipt or net | `settlements.changed`, with no IDs or amounts; refetch reviews, groups and history | `settlement.created` (each party), `settlement.line.changed`, `settlement.group.changed`, `settlement.net.changed` |
| `/user/queue/me` | The user only | `tables.changed`, `invitations.changed` (no IDs) or `profile.changed` with the user ID | see below |
| `/user/queue/ticker` | Every signed-in player | `ticker.changed`, no IDs; refetch `GET /ticker` | `sports.games.changed` (one per feed poll that changed a game) |

Personal-feed audiences, looked up when each event is delivered:

- `tables.changed`: all members when a table is created, renamed or a host
  changes; the user who joined or was removed; everyone snapshotted when a
  table is deleted.
- `invitations.changed`: the invitee of a new invitation, and every pending
  invitee when the table's name or emoji changes. Invitees never gain access to
  table feeds.
- `profile.changed`: the user and everyone who shares a current table or a
  receipt with them, excluding anyone in a block relationship with them in
  either direction. It names only the user whose profile changed.

Permission changes reveal only that the table changed; the HTTP table view
already limits which permissions each viewer can see. Quick Pick invalidations
contain only the affected table ID, never the source's code or cross-table
relationships. A removed member stops receiving table feeds at once because
every delivery rechecks membership.

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
event keeps its ID. `resourceId` is the affected bet for `bet.changed` and
the affected user for `profile.changed`. `bets.changed` and `table.changed`
name only the table; `settlements.changed`, `tables.changed` and
`invitations.changed` carry no table or resource ID. The envelope contains no pick, message
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
feeds. `sports.SportsIntegrationTest` covers the ticker targets (6–7) on a
daylight-saving date with a scripted feed.

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
