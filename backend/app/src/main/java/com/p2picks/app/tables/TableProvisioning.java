package com.p2picks.app.tables;

import com.p2picks.app.chat.ChatMessageWriter;
import com.p2picks.app.common.MembershipLimits;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tables the server sets up itself rather than a player's request (the LAN party table): no session, retry key or
 * invite settings, but the same rows, realtime events and chat lines as {@link TableCreationService}. Runs inside
 * the caller's transaction.
 */
@Service
public class TableProvisioning {
    private final JdbcTemplate jdbc;
    private final ChatMessageWriter chatMessages;
    private final Outbox outbox;

    TableProvisioning(JdbcTemplate jdbc, ChatMessageWriter chatMessages, Outbox outbox) {
        this.jdbc = jdbc;
        this.chatMessages = chatMessages;
        this.outbox = outbox;
    }

    /** A new table hosted by {@code hostId}; returns its id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createHosted(UUID hostId, String name, String emoji) {
        if (!TableProfileValidation.validEmoji(emoji)) throw new IllegalArgumentException("Not an emoji: " + emoji);
        UUID tableId = UUID.randomUUID();
        insertTable(jdbc, tableId, name, emoji);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", tableId, hostId);
        outbox.append(OutboxEvent.TABLE_CREATED, tableId, Map.of("tableId", tableId));
        chatMessages.system(tableId, "Table created");
        return tableId;
    }

    /** Whether the table exists and hasn't been deleted (archived). */
    @Transactional(readOnly = true)
    public boolean isOpen(UUID tableId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM tables WHERE id = ? AND archived_at IS NULL)", Boolean.class, tableId));
    }

    /**
     * Adds {@code userId} as a member, unless the table is gone (archived), they're already in it, or it's full.
     * Returns whether they were seated.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean seat(UUID tableId, UUID userId) {
        if (jdbc.queryForList("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE", UUID.class,
                tableId).isEmpty()) {
            return false;
        }
        if (MembershipLimits.members(jdbc, tableId) >= MembershipLimits.MEMBERS_PER_TABLE) return false;
        int inserted = jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')
                ON CONFLICT DO NOTHING
                """, tableId, userId);
        if (inserted == 0) return false;
        outbox.append(OutboxEvent.TABLE_MEMBER_JOINED, tableId, Map.of("tableId", tableId, "userId", userId));
        chatMessages.system(tableId, "Members were added to the table");
        return true;
    }

    /** The table's current members (hosts included), oldest first; empty if it's gone. */
    @Transactional(readOnly = true)
    public List<UUID> members(UUID tableId) {
        return jdbc.queryForList("""
                SELECT m.user_id FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.table_id = ? AND t.archived_at IS NULL ORDER BY m.joined_at, m.user_id
                """, UUID.class, tableId);
    }

    /** Inserts the table row with a fresh, unique Quick Pick code. */
    static void insertTable(JdbcTemplate jdbc, UUID tableId, String name, String emoji) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int inserted = jdbc.update("""
                    INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)
                    ON CONFLICT (quickpick_code) DO NOTHING
                    """, tableId, name, emoji, QuickPickCodes.generate());
            if (inserted == 1) return;
        }
        throw new IllegalStateException("Could not allocate a unique Quick Pick code");
    }
}
