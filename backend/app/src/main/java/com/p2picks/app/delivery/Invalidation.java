package com.p2picks.app.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A realtime invalidation (api/realtime.schema.json, version 1). It tells a client what to refetch and never
 * carries picks, message content, usernames or amounts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Invalidation(UUID eventId, String type, int schemaVersion, String occurredAt, UUID tableId, UUID resourceId) {
    static final String BET_CHANGED = "bet.changed";
    static final String BETS_CHANGED = "bets.changed";
    static final String CHAT_CHANGED = "chat.changed";
    static final String SETTLEMENTS_CHANGED = "settlements.changed";
    static final String TABLE_CHANGED = "table.changed";
    static final String TABLES_CHANGED = "tables.changed";
    static final String INVITATIONS_CHANGED = "invitations.changed";
    static final String PROFILE_CHANGED = "profile.changed";
    static final String TICKER_CHANGED = "ticker.changed";

    /**
     * The event ID is derived from the outbox event and the invalidation type, so a redelivered outbox event
     * yields the same ID and clients can drop the duplicate.
     */
    static Invalidation of(UUID outboxId, String type, String occurredAt, UUID tableId, UUID resourceId) {
        UUID eventId = UUID.nameUUIDFromBytes((outboxId + "|" + type).getBytes(StandardCharsets.UTF_8));
        return new Invalidation(eventId, type, 1, occurredAt, tableId, resourceId);
    }
}
