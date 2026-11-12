# Realtime contract — Phase 0 draft

This specifies intended behavior, not an implemented gateway. The v3 plan uses
WebSocket/STOMP. HTTP reads remain authoritative and provide recovery when
realtime delivery is interrupted.

## Connection and authorization

- Authenticate the WebSocket handshake with the same active HttpOnly-cookie
  session as HTTP and reject an unapproved `Origin`. The STOMP `CONNECT`
  frame must carry the CSRF token obtained from `/auth/csrf` in its
  `X-CSRF-TOKEN` header; do not put credentials or tokens in a URL. An
  authenticated handshake alone does not authorize a subscription.
- Expiry, logout, account freeze or deletion invalidates the connection and
  subscriptions. Revalidate session and membership before delivery so a
  missed revocation notification cannot continue leaking updates. Terminate
  expired or revoked connections; do not depend on Redis for revocation.
- Authorize every subscription and re-check access when membership, account
  status or session validity changes. A table topic is restricted to current
  members; a user queue is restricted to its authenticated owner.
- Never rely on the client to hide data. In particular, a shared table message
  cannot contain an Active bet's individual picks or a caller-specific
  `myPick`. The authorized HTTP bet view supplies the requesting user's own
  pick. Block filtering must be applied on the server for each recipient.
- Reject unauthorized sends and subscriptions; browser clients cannot publish
  sports results, settlement transitions or other authoritative domain events.

## Destinations

| Destination | Audience | Planned payload |
|---|---|---|
| `/topic/tables/{id}/chat` | Authorized table members | `chat.changed`, a table-level invalidation without a message identity; fetch the filtered HTTP window. |
| `/topic/tables/{id}/bets` | Authorized table members | `bet.changed` with bet identity; fetch the caller's authorized bet view. |
| `/user/queue/notifications` | Authenticated user only | `notifications.changed`; fetch the user's notification page. |
| `/topic/ticker` | Authenticated users | `ticker.changed`, a generic invalidation without private identities; refetch `GET /ticker`. |

These are invalidation messages, not complete chat or bet projections. That
avoids broadcasting one recipient's private view to other subscribers.
The internal `table.member.permission.changed.v1` outbox event is not a
shared-table event: only current hosts and the affected member may learn the
permission change. A future relay must reauthorize those recipients and design
an appropriate private invalidation before delivering it; no such destination
or delivery is implemented.
The internal `table.remainder.changed.v1` and system
`chat.message.created.v1` events likewise require a future relay. A table-view
invalidation destination is not yet defined; do not treat either event as an
implemented client notification.
Host changes also write internal table and system-chat events. They are not
delivery; a future relay must reauthorize recipients and invalidate the
relevant table and filtered chat views without treating system lines as
ordinary user-chat notifications.
Table-profile changes also affect pending invitation views. A future relay must
invalidate those views privately for currently eligible recipients; invitees
must not gain access to table topics. No such delivery is implemented.
Quick Pick controls also write table invalidations for affected subscriber
tables. These contain only the affected table ID; future delivery must recheck
membership and must not expose the source's code or cross-table relationships
to unauthorized recipients. No relay or table invalidation destination exists.
Member removal writes an internal event containing the removed user ID.
Future delivery must immediately revoke that user's access and privately
invalidate their own table list while rechecking authorization; never broadcast
the raw internal event without an explicit audience and privacy review.
Table deletion writes an internal archive event; future delivery must stop
active table subscriptions and privately invalidate eligible archive lists
while rechecking access. No such delivery is implemented.
Personal profile changes write an internal event containing only a user ID.
Future profile invalidations need recipient-specific authorization and block
filtering; the raw event must not be broadcast to all clients.

## Invalidation envelope

The versioned schema is `../../backend/api/realtime.schema.json`. Destinations
accept only the corresponding event type in the table above. A chat event has
no message ID: a shared topic cannot safely contain a recipient-specific
blocked-message view. A table member may observe that the table changed, but
message content and identity must come only from the filtered HTTP response.
Notification and ticker invalidations contain no table or resource ID.
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

`eventId` identifies the emitted event for deduplication; `resourceId` is the
affected bet for `bet.changed` only. The envelope contains no pick, message
identity or text, username, credential or financial amount. The schema and its
tests describe this draft contract, not an implemented gateway.

## Delivery and recovery

- Realtime invalidations are best effort and may be delayed, duplicated or
  missed. Event IDs support deduplication; clients must tolerate reordered
  invalidations and refetch current HTTP state rather than treating event order
  as authoritative.
- After connect, reconnect, session renewal, membership change or a failed
  refresh, clients subscribe and then refetch relevant authorized resources
  and reconcile them with local state. An invalidation received during a
  refetch must schedule another refresh so a late response cannot hide a
  change. Dev chat history uses opaque before/after cursors and an around
  message ID. Retain the last nonempty window's next cursor to fetch arrivals
  even after reaching the current end; an empty page has no replacement cursor.
  Refetch existing windows after block changes: a newly visible message can be
  older than the saved next cursor.
- PostgreSQL remains authoritative; Kafka outbox consumers and Redis fan-out
  must not be used as proof that a client received an event. Redis loss may
  interrupt realtime updates; recovery must trigger resynchronization.
- Ordering, replay and gap detection beyond refetch-on-reconnect remain open
  design work. Do not promise exactly-once delivery.

## Verification targets

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
