package com.p2picks.app;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatReadService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, NOT_FOUND }

    record UnreadState(UUID firstUnreadMessageId, long unreadCount) {}
    record ReadResult(boolean authenticated, boolean accessible, UnreadState state) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;

    ChatReadService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
    }

    ReadResult read(Jwt jwt, UUID tableId) {
        List<ReadResult> results = jdbc.query(("""
                SELECT t.id AS table_id, unread.id AS first_unread, COALESCE(unread.total, 0) AS total
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN table_members member ON member.table_id = ? AND member.user_id = viewer.id
                LEFT JOIN tables t ON t.id = member.table_id AND t.archived_at IS NULL
                LEFT JOIN chat_read_positions pos ON pos.table_id = t.id AND pos.user_id = viewer.id
                LEFT JOIN LATERAL (
                    SELECT m.id, count(*) OVER () AS total
                    FROM chat_messages m
                    LEFT JOIN users author ON author.id = m.author_id
                    WHERE m.table_id = t.id AND m.message_order > COALESCE(pos.last_read_order, 0)
                      AND m.author_id IS DISTINCT FROM viewer.id AND %s
                    ORDER BY m.message_order LIMIT 1
                ) unread ON true
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """).formatted(ChatVisibility.VISIBLE), (rs, row) -> new ReadResult(true,
                        rs.getObject("table_id", UUID.class) != null,
                        new UnreadState(rs.getObject("first_unread", UUID.class), rs.getLong("total"))),
                tableId, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        return results.isEmpty() ? new ReadResult(false, false, null) : results.getFirst();
    }

    @Transactional
    public Outcome advance(Jwt jwt, UUID tableId, UUID messageId, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty() || jdbc.query("""
                SELECT user_id FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tableId, userId).isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Long> positions = jdbc.query(("""
                SELECT m.message_order FROM chat_messages m
                JOIN users viewer ON viewer.id = ?
                LEFT JOIN users author ON author.id = m.author_id
                WHERE m.id = ? AND m.table_id = ? AND %s FOR SHARE OF m
                """).formatted(ChatVisibility.VISIBLE),
                (rs, row) -> rs.getLong("message_order"), userId, messageId, tableId);
        if (positions.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "chat.read-position.v1",
                tableId + "|" + messageId);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        long position = positions.getFirst();
        jdbc.update("""
                INSERT INTO chat_read_positions (table_id, user_id, last_read_order)
                VALUES (?, ?, ?)
                ON CONFLICT (table_id, user_id) DO UPDATE
                SET last_read_order = EXCLUDED.last_read_order, updated_at = clock_timestamp()
                WHERE chat_read_positions.last_read_order < EXCLUDED.last_read_order
                """, tableId, userId, position);
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}
