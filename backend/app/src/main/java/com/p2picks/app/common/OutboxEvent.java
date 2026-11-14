package com.p2picks.app.common;

/**
 * Every event the application records in the transactional outbox, with the aggregate type it is
 * keyed by. Event names are versioned; change a payload by adding a new version, not by editing one.
 * Nothing relays these events yet (see ADR 0002).
 */
public enum OutboxEvent {
    USER_PROFILE_CHANGED("user", "user.profile.changed.v1"),

    TABLE_CREATED("table", "table.created.v1"),
    TABLE_PROFILE_CHANGED("table", "table.profile.changed.v1"),
    TABLE_SETTINGS_CHANGED("table", "table.settings.changed.v1"),
    TABLE_REMAINDER_CHANGED("table", "table.remainder.changed.v1"),
    TABLE_QUICKPICKS_CHANGED("table", "table.quickpicks.changed.v1"),
    TABLE_HOST_CHANGED("table", "table.host.changed.v1"),
    TABLE_MEMBER_JOINED("table", "table.member.joined.v1"),
    TABLE_MEMBER_REMOVED("table", "table.member.removed.v1"),
    TABLE_MEMBER_PERMISSION_CHANGED("table", "table.member.permission.changed.v1"),
    TABLE_ARCHIVED("table", "table.archived.v1"),
    /** A member threw a tomato at another; the payload names only the table. */
    TABLE_TOMATO_THROWN("table", "table.tomato.thrown.v1"),
    /** Keyed by the invited user so it can reach them before they join the table. */
    TABLE_INVITATION_CREATED("user", "table.invitation.created.v1"),

    CHAT_MESSAGE_CREATED("chat", "chat.message.created.v1"),
    CHAT_REACTION_CHANGED("chat", "chat.reaction.changed.v1"),

    BET_CREATED("bet", "bet.created.v1"),
    BET_PICK_COUNT_CHANGED("bet", "bet.pick_count.changed.v1"),
    BET_LOCKED("bet", "bet.locked.v1"),
    BET_RESOLVED("bet", "bet.resolved.v1"),

    SETTLEMENT_CREATED("settlement", "settlement.created.v1"),
    SETTLEMENT_LINE_CHANGED("settlement_line", "settlement.line.changed.v1"),
    /** Keyed by "userId:otherUserId". */
    SETTLEMENT_GROUP_CHANGED("settlement_pair", "settlement.group.changed.v1"),
    /** Keyed by "userId:otherUserId"; the payload carries numeric beforeCents and afterCents. */
    SETTLEMENT_NET_CHANGED("settlement_pair", "settlement.net.changed.v1"),

    /** A feed poll changed at least one game snapshot; keyed by league. */
    SPORTS_GAMES_CHANGED("sports_feed", "sports.games.changed.v1");

    private final String aggregateType;
    private final String eventType;

    OutboxEvent(String aggregateType, String eventType) {
        this.aggregateType = aggregateType;
        this.eventType = eventType;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String eventType() {
        return eventType;
    }
}
