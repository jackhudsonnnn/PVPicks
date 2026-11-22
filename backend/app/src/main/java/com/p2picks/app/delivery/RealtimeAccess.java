package com.p2picks.app.delivery;

import java.sql.Array;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database checks behind every realtime subscription and delivery; PostgreSQL is authoritative, not Redis. */
@Component
class RealtimeAccess implements Audiences {
    private final JdbcTemplate jdbc;

    RealtimeAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** IDs of sessions that are unrevoked, unexpired and belong to an active player with the given user ID. */
    Set<UUID> activeSessions(Collection<SessionIdentity> sessions) {
        if (sessions.isEmpty()) return Set.of();
        Set<UUID> active = new HashSet<>();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT s.id, s.user_id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ANY(?) AND s.revoked_at IS NULL AND s.expires_at > now()
                      AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                    """);
            statement.setArray(1, uuidArray(connection, sessions.stream().map(SessionIdentity::sessionId).toList()));
            return statement;
        }, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID user = rs.getObject("user_id", UUID.class);
            if (sessions.contains(new SessionIdentity(id, user))) active.add(id);
        });
        return active;
    }

    /** Which of the given users are current members of the non-archived table. */
    Set<UUID> currentMembers(UUID tableId, Collection<UUID> users) {
        if (users.isEmpty()) return Set.of();
        Set<UUID> members = new HashSet<>();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT m.user_id FROM table_members m JOIN tables t ON t.id = m.table_id
                    WHERE m.table_id = ? AND t.archived_at IS NULL AND m.user_id = ANY(?)
                    """);
            statement.setObject(1, tableId);
            statement.setArray(2, uuidArray(connection, users));
            return statement;
        }, rs -> {
            members.add(rs.getObject("user_id", UUID.class));
        });
        return members;
    }

    @Override
    public Set<UUID> settlementParties(UUID settlementId) {
        return ids("""
                SELECT payer_id FROM settlement_lines WHERE settlement_id = ?
                UNION SELECT payee_id FROM settlement_lines WHERE settlement_id = ?
                """, settlementId, settlementId);
    }

    @Override
    public Set<UUID> tableMembers(UUID tableId) {
        return ids("""
                SELECT m.user_id FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.table_id = ? AND t.archived_at IS NULL
                """, tableId);
    }

    @Override
    public Set<UUID> tableHosts(UUID tableId) {
        return ids("""
                SELECT m.user_id FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.table_id = ? AND t.archived_at IS NULL AND m.role = 'HOST'
                """, tableId);
    }

    @Override
    public Set<UUID> pendingInvitees(UUID tableId) {
        return ids("SELECT user_id FROM table_invites WHERE table_id = ? AND status = 'PENDING'", tableId);
    }

    @Override
    public Set<UUID> archiveMembers(UUID tableId) {
        return ids("SELECT user_id FROM table_archive_members WHERE table_id = ?", tableId);
    }

    @Override
    public Set<UUID> profileAudience(UUID userId) {
        return ids("""
                SELECT other FROM (
                    SELECT them.user_id AS other
                    FROM table_members me
                    JOIN tables t ON t.id = me.table_id AND t.archived_at IS NULL
                    JOIN table_members them ON them.table_id = me.table_id
                    WHERE me.user_id = ?
                    UNION SELECT payee_id FROM settlement_lines WHERE payer_id = ?
                    UNION SELECT payer_id FROM settlement_lines WHERE payee_id = ?
                ) audience
                WHERE other <> ? AND NOT EXISTS (
                    SELECT 1 FROM blocks b
                    WHERE (b.blocker_id = ? AND b.blocked_id = audience.other)
                       OR (b.blocker_id = audience.other AND b.blocked_id = ?))
                """, userId, userId, userId, userId, userId, userId);
    }

    private Set<UUID> ids(String sql, Object... arguments) {
        Set<UUID> ids = new HashSet<>();
        jdbc.query(sql, rs -> {
            ids.add(rs.getObject(1, UUID.class));
        }, arguments);
        return ids;
    }

    private static Array uuidArray(java.sql.Connection connection, Collection<UUID> ids) throws java.sql.SQLException {
        return connection.createArrayOf("uuid", ids.toArray());
    }

    record SessionIdentity(UUID sessionId, UUID userId) {}
}
