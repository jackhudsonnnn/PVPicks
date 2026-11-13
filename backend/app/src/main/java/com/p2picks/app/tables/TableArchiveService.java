package com.p2picks.app.tables;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.TableSettlementGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TableArchiveService {
    enum Outcome { ARCHIVED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Archive(UUID id, String name, String emoji, OffsetDateTime archivedAt, OffsetDateTime expiresAt) {}
    record ListResult(boolean authenticated, List<Archive> archives) {}
    private record PreliminaryAccess(String role) {}
    static class UnsettledBets extends RuntimeException {}
    static class MembershipChanged extends RuntimeException {}
    static class AlreadyArchived extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final QuickPickSubscriptions subscriptions;
    private final TableSettlementGuard settlementGuard;

    TableArchiveService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            QuickPickSubscriptions subscriptions, TableSettlementGuard settlementGuard) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.subscriptions = subscriptions;
        this.settlementGuard = settlementGuard;
    }

    ListResult list(Jwt jwt) {
        return jdbc.query("""
                SELECT a.id, a.name, a.emoji, a.archived_at, a.archived_at + interval '2160 hours' AS expires_at
                FROM auth_sessions session JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN table_archive_members member ON member.user_id = viewer.id
                LEFT JOIN tables a ON a.id = member.table_id AND a.archived_at IS NOT NULL
                    AND a.archived_at + interval '2160 hours' > now()
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                    AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                ORDER BY a.archived_at DESC, a.id
                """, rs -> {
                    if (!rs.next()) return new ListResult(false, List.of());
                    var archives = new java.util.ArrayList<Archive>();
                    do {
                        UUID id = rs.getObject("id", UUID.class);
                        if (id != null) archives.add(new Archive(id, rs.getString("name"), rs.getString("emoji"),
                                rs.getObject("archived_at", OffsetDateTime.class),
                                rs.getObject("expires_at", OffsetDateTime.class)));
                    } while (rs.next());
                    return new ListResult(true, List.copyOf(archives));
                }, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
    }

    @Transactional
    public Outcome archive(Jwt jwt, UUID tableId, String key) {
        UUID actor = UUID.fromString(jwt.getSubject());
        List<PreliminaryAccess> access = jdbc.query("""
                SELECT member.role FROM auth_sessions session JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN table_members member ON member.table_id = ? AND member.user_id = viewer.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                    AND session.expires_at > clock_timestamp() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new PreliminaryAccess(rs.getString("role")),
                tableId, UUID.fromString(jwt.getId()), actor);
        if (access.isEmpty()) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (access.getFirst().role() == null) return Outcome.NOT_FOUND;
        if (!access.getFirst().role().equals("HOST")) return Outcome.FORBIDDEN;
        List<UUID> members = memberIds(tableId);
        Map<UUID, String> statuses = authentication.lockActiveWithTargets(jwt, members);
        if (statuses == null) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<Boolean> tables = jdbc.query("SELECT archived_at IS NOT NULL FROM tables WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<UUID> lockedMembers = memberIds(tableId);
        if (!members.equals(lockedMembers)) throw new MembershipChanged();
        List<String> roles = jdbc.query("""
                SELECT role FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getString("role"), tableId, actor);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return Outcome.NOT_FOUND;
        if (!roles.getFirst().equals("HOST")) return Outcome.FORBIDDEN;
        if (tables.getFirst() && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM table_archive_members WHERE table_id = ? AND user_id = ?)
                """, Boolean.class, tableId, actor))) return Outcome.NOT_FOUND;
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, "table.archive.v1", tableId.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (tables.getFirst()) throw new AlreadyArchived();
        boolean blocked = settlementGuard.hasUnsettledBets(tableId);
        authentication.requireUnexpired(jwt);
        if (blocked) throw new UnsettledBets();

        jdbc.update("""
                INSERT INTO table_archive_members (table_id, user_id)
                SELECT table_id, user_id FROM table_members WHERE table_id = ?
                """, tableId);
        jdbc.update("UPDATE table_invites SET status = 'CANCELLED' WHERE table_id = ? AND status = 'PENDING'", tableId);
        subscriptions.disconnect(tableId, true);
        jdbc.update("""
                UPDATE tables SET archived_at = clock_timestamp(), quickpicks_enabled = false, quickpick_sharing = false
                WHERE id = ?
                """, tableId);
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'table', ?, 'table.archived.v1', jsonb_build_object('tableId', ?::text))
                """, UUID.randomUUID(), tableId.toString(), tableId.toString());
        authentication.requireUnexpired(jwt);
        return Outcome.ARCHIVED;
    }

    private List<UUID> memberIds(UUID tableId) {
        return jdbc.query("SELECT user_id FROM table_members WHERE table_id = ? ORDER BY user_id",
                (rs, row) -> rs.getObject("user_id", UUID.class), tableId);
    }
}
