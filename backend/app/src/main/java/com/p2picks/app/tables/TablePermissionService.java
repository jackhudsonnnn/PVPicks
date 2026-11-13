package com.p2picks.app.tables;

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
class TablePermissionService {
    enum Permission {
        BETS("bets", "can_bet"),
        TEXT("text", "can_text"),
        REACT("react", "can_react"),
        GIFS("gifs", "can_send_gifs");

        private final String value;
        private final String column;

        Permission(String value, String column) {
            this.value = value;
            this.column = column;
        }

        static Permission parse(String value) {
            for (Permission permission : values()) {
                if (permission.value.equals(value)) return permission;
            }
            return null;
        }
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, HOST_TARGET }

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    TablePermissionService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public Outcome set(Jwt jwt, UUID tableId, UUID targetId, Permission permission, boolean enabled, String key) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        MutationAuthentication.PairStatus pair = authentication.lockActivePair(jwt, targetId);
        if (pair == MutationAuthentication.PairStatus.UNAUTHORIZED) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (pair == MutationAuthentication.PairStatus.TARGET_UNAVAILABLE) return Outcome.NOT_FOUND;
        List<UUID> tables = jdbc.query("""
                SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Member> members = jdbc.query("""
                SELECT user_id, role FROM table_members
                WHERE table_id = ? AND user_id IN (?, ?) ORDER BY user_id FOR UPDATE
                """, (rs, row) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("role")),
                tableId, actorId, targetId);
        Member actor = members.stream().filter(member -> member.userId().equals(actorId)).findFirst().orElse(null);
        if (actor == null) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        if (!actor.role().equals("HOST")) {
            authentication.requireUnexpired(jwt);
            return Outcome.FORBIDDEN;
        }
        Member target = members.stream().filter(member -> member.userId().equals(targetId)).findFirst().orElse(null);
        if (target == null) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        if (target.role().equals("HOST")) {
            authentication.requireUnexpired(jwt);
            return Outcome.HOST_TARGET;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(actorId, key, "table.permission.set.v1",
                tableId + "|" + targetId + "|" + permission.value + "|" + enabled);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        int changed = jdbc.update("""
                UPDATE table_members SET %s = ?
                WHERE table_id = ? AND user_id = ? AND %s IS DISTINCT FROM ?
                """.formatted(permission.column, permission.column), enabled, tableId, targetId, enabled);
        if (changed != 0) {
            outbox.append(OutboxEvent.TABLE_MEMBER_PERMISSION_CHANGED, tableId,
                    Map.of("tableId", tableId, "userId", targetId));
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private record Member(UUID userId, String role) {}
}
