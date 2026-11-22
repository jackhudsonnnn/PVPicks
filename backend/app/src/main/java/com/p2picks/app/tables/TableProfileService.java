package com.p2picks.app.tables;

import com.p2picks.app.chat.ChatMessageWriter;
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
class TableProfileService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Changes(String name, String emoji) {}
    private record Profile(String name, String emoji) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final ChatMessageWriter chatMessages;
    private final Outbox outbox;

    TableProfileService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            ChatMessageWriter chatMessages, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.chatMessages = chatMessages;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome update(Jwt jwt, UUID tableId, Changes changes, String key) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<Profile> tables = jdbc.query("""
                SELECT name, emoji FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Profile(rs.getString("name"), rs.getString("emoji")), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, userId);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return Outcome.NOT_FOUND;
        if (!roles.getFirst().equals("HOST")) return Outcome.FORBIDDEN;

        String request = tableId + "|" + canonical(changes.name()) + "|" + canonical(changes.emoji());
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "table.profile.update.v1",
                request, IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;

        Profile current = tables.getFirst();
        boolean changed = changes.name() != null && !changes.name().equals(current.name())
                || changes.emoji() != null && !changes.emoji().equals(current.emoji());
        if (changed) {
            jdbc.update("""
                    UPDATE tables SET name = COALESCE(?, name), emoji = COALESCE(?, emoji) WHERE id = ?
                    """, changes.name(), changes.emoji(), tableId);
            chatMessages.system(tableId, "Table details changed");
            outbox.append(OutboxEvent.TABLE_PROFILE_CHANGED, tableId, Map.of("tableId", tableId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private static String canonical(String value) {
        return value == null ? "-" : value.length() + ":" + value;
    }
}
