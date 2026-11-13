package com.p2picks.app.chat;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReactionService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    ReactionService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome change(Jwt jwt, UUID messageId, String emoji, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<UUID> hints = jdbc.query("SELECT table_id FROM chat_messages WHERE id = ?",
                (rs, row) -> rs.getObject("table_id", UUID.class), messageId);
        if (hints.isEmpty()) return Outcome.NOT_FOUND;
        UUID tableId = hints.getFirst();
        List<UUID> tables = jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Boolean> permissions = jdbc.query("""
                SELECT can_react FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getBoolean("can_react"), tableId, userId);
        if (permissions.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<UUID> messages = jdbc.query(("""
                SELECT m.id FROM chat_messages m
                JOIN users viewer ON viewer.id = ?
                LEFT JOIN users author ON author.id = m.author_id
                WHERE m.id = ? AND m.table_id = ? AND m.kind IN ('text', 'gif', 'bet')
                  AND %s FOR SHARE OF m
                """).formatted(ChatVisibility.VISIBLE),
                (rs, row) -> rs.getObject("id", UUID.class), userId, messageId, tableId);
        if (messages.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        if (!permissions.getFirst()) {
            authentication.requireUnexpired(jwt);
            return Outcome.FORBIDDEN;
        }
        String operation = emoji == null ? "reaction.remove.v1" : "reaction.put.v1";
        String request = emoji == null ? messageId.toString() : messageId + "|" + emoji;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation, request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        int changed;
        if (emoji == null) {
            changed = jdbc.update("DELETE FROM reactions WHERE message_id = ? AND user_id = ?", messageId, userId);
        } else {
            changed = jdbc.update("""
                    INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)
                    ON CONFLICT (message_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji
                    WHERE reactions.emoji IS DISTINCT FROM EXCLUDED.emoji
                    """, messageId, userId, emoji);
        }
        if (changed != 0) {
            outbox.append(OutboxEvent.CHAT_REACTION_CHANGED, tableId, Map.of("tableId", tableId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

}
