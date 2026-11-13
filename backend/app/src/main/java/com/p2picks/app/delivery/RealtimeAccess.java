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
class RealtimeAccess {
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

    /** Both parties of every receipt line in a points settlement. */
    Set<UUID> settlementParties(UUID settlementId) {
        Set<UUID> parties = new HashSet<>();
        jdbc.query("SELECT payer_id, payee_id FROM settlement_lines WHERE settlement_id = ?", rs -> {
            parties.add(rs.getObject("payer_id", UUID.class));
            parties.add(rs.getObject("payee_id", UUID.class));
        }, settlementId);
        return parties;
    }

    private static Array uuidArray(java.sql.Connection connection, Collection<UUID> ids) throws java.sql.SQLException {
        return connection.createArrayOf("uuid", ids.toArray());
    }

    record SessionIdentity(UUID sessionId, UUID userId) {}
}
