package com.p2picks.app.tables;

import com.p2picks.app.chat.ChatMessageWriter;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What tables do when an account stops being active (frozen or deleted). Callers hold the account's user lock
 * and run inside their transaction; this locks each affected table (users, then table, the documented order).
 *
 * <p>Host recovery: a table whose last active host is gone gets a new host, the member who joined earliest among
 * those still active, announced in chat like any promotion. If nobody active is left, the table is archived
 * (former members keep read-only chat for 90 days), even with open bets, since no one could settle them.
 */
@Component
public class TableAccountChanges {
    private final JdbcTemplate jdbc;
    private final ChatMessageWriter chatMessages;
    private final Outbox outbox;
    private final QuickPickSubscriptions subscriptions;

    TableAccountChanges(JdbcTemplate jdbc, ChatMessageWriter chatMessages, Outbox outbox,
            QuickPickSubscriptions subscriptions) {
        this.jdbc = jdbc;
        this.chatMessages = chatMessages;
        this.outbox = outbox;
        this.subscriptions = subscriptions;
    }

    /** The account was frozen: it stays a member everywhere, but tables it hosted may need a new host. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivated(UUID userId) {
        for (UUID table : hostedTables(userId)) {
            lockTable(table);
            recoverHost(table);
        }
    }

    /**
     * The account is being deleted: it leaves every current table (its archived memberships are dropped too),
     * pending invitations to or from it are cancelled, its tomatoes are removed, and tables it hosted are recovered.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        List<UUID> tables = jdbc.query("""
                SELECT m.table_id FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.user_id = ? AND t.archived_at IS NULL ORDER BY m.table_id
                """, (rs, row) -> rs.getObject("table_id", UUID.class), userId);
        for (UUID table : tables) {
            lockTable(table);
            jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, userId);
            chatMessages.system(table, "A member was removed");
            outbox.append(OutboxEvent.TABLE_MEMBER_REMOVED, table, Map.of("tableId", table, "removedUserId", userId));
            recoverHost(table);
        }
        jdbc.update("DELETE FROM table_members WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM table_archive_members WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE table_invites SET status = 'CANCELLED'
                WHERE (user_id = ? OR from_id = ?) AND status = 'PENDING'
                """, userId, userId);
        jdbc.update("DELETE FROM tomato_throws WHERE thrower_id = ? OR target_id = ?", userId, userId);
    }

    private List<UUID> hostedTables(UUID userId) {
        return jdbc.query("""
                SELECT m.table_id FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.user_id = ? AND m.role = 'HOST' AND t.archived_at IS NULL ORDER BY m.table_id
                """, (rs, row) -> rs.getObject("table_id", UUID.class), userId);
    }

    private void lockTable(UUID tableId) {
        jdbc.query("SELECT id FROM tables WHERE id = ? FOR UPDATE", rs -> { }, tableId);
    }

    /** Gives the table to its earliest-joined active member if no active host remains, or archives it. */
    private void recoverHost(UUID tableId) {
        Boolean hasHost = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM table_members m JOIN users u ON u.id = m.user_id
                               WHERE m.table_id = ? AND m.role = 'HOST' AND u.status = 'ACTIVE')
                """, Boolean.class, tableId);
        if (Boolean.TRUE.equals(hasHost)) return;
        List<UUID> next = jdbc.query("""
                SELECT m.user_id FROM table_members m JOIN users u ON u.id = m.user_id
                WHERE m.table_id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                ORDER BY m.joined_at, m.user_id LIMIT 1 FOR UPDATE OF m
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tableId);
        if (!next.isEmpty()) {
            UUID host = next.getFirst();
            jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", tableId, host);
            outbox.append(OutboxEvent.TABLE_HOST_CHANGED, tableId, Map.of("tableId", tableId));
            chatMessages.attributedSystem(tableId, "A member is now a host", "host_promoted", host);
            return;
        }
        jdbc.update("""
                INSERT INTO table_archive_members (table_id, user_id)
                SELECT table_id, user_id FROM table_members WHERE table_id = ?
                ON CONFLICT DO NOTHING
                """, tableId);
        jdbc.update("UPDATE table_invites SET status = 'CANCELLED' WHERE table_id = ? AND status = 'PENDING'", tableId);
        subscriptions.disconnect(tableId, true);
        jdbc.update("""
                UPDATE tables SET archived_at = clock_timestamp(), quickpicks_enabled = false, quickpick_sharing = false
                WHERE id = ?
                """, tableId);
        outbox.append(OutboxEvent.TABLE_ARCHIVED, tableId, Map.of("tableId", tableId));
    }
}
