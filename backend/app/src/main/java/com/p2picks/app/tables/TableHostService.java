package com.p2picks.app.tables;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableHostService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    static class LastActiveHost extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;

    TableHostService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
    }

    @Transactional
    public Outcome set(Jwt jwt, UUID tableId, UUID targetId, boolean host, String key) {
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
        authentication.requireUnexpired(jwt);
        if (actor == null) return Outcome.NOT_FOUND;
        if (!actor.role().equals("HOST")) return Outcome.FORBIDDEN;
        Member target = members.stream().filter(member -> member.userId().equals(targetId)).findFirst().orElse(null);
        if (target == null) return Outcome.NOT_FOUND;

        IdempotencyGuard.Claim claim = idempotency.claim(actorId, key, "table.host.set.v1",
                tableId + "|" + targetId + "|" + host);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (target.role().equals("HOST") != host) {
            if (!host) {
                boolean remaining = Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM table_members m JOIN users u ON u.id = m.user_id
                            WHERE m.table_id = ? AND m.role = 'HOST' AND m.user_id <> ? AND u.status = 'ACTIVE'
                        )
                        """, Boolean.class, tableId, targetId), "Active host check returned null");
                authentication.requireUnexpired(jwt);
                if (!remaining) throw new LastActiveHost();
            }
            jdbc.update("""
                    UPDATE table_members SET role = ?, can_bet = true, can_text = true,
                        can_react = true, can_send_gifs = true WHERE table_id = ? AND user_id = ?
                    """, host ? "HOST" : "MEMBER", tableId, targetId);
            if (!host) {
                jdbc.update("""
                        UPDATE table_invites SET status = 'CANCELLED'
                        WHERE table_id = ? AND from_id = ? AND status = 'PENDING'
                        """, tableId, targetId);
            }
            UUID messageId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                    VALUES (?, ?, 'system', ?, ?, ?)
                    """, messageId, tableId, host ? "A member is now a host" : "A member is no longer a host",
                    host ? "host_promoted" : "host_demoted", targetId);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'table', ?, 'table.host.changed.v1', jsonb_build_object('tableId', ?::text)),
                           (?, 'chat', ?, 'chat.message.created.v1',
                            jsonb_build_object('tableId', ?::text, 'messageId', ?::text, 'kind', 'system'))
                    """, UUID.randomUUID(), tableId.toString(), tableId.toString(),
                    UUID.randomUUID(), tableId.toString(), tableId.toString(), messageId.toString());
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private record Member(UUID userId, String role) {}
}
