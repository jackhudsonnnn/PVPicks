package com.p2picks.app.tables;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tomatoes: a member throws one at another member and everyone at the table sees it land. Purely playful: it never
 * touches points or reports. Throwing needs the member's reaction permission, and nobody can throw at, or see
 * throws involving, someone they're in a block relationship with.
 */
@Service
class TomatoService {
    /** One throw per thrower per table in this window. */
    static final int COOLDOWN_SECONDS = 5;
    /** How far back a read looks: long enough to survive a realtime round trip and a reconnect. */
    static final int RECENT_SECONDS = 30;

    enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, RATE_LIMITED }

    record Throw(UUID id, UUID throwerId, UUID targetId, OffsetDateTime thrownAt) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final Outbox outbox;

    TomatoService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    private static final String NO_BLOCK = """
            NOT EXISTS (SELECT 1 FROM blocks b
                        WHERE (b.blocker_id = ? AND b.blocked_id = ?) OR (b.blocker_id = ? AND b.blocked_id = ?))
            """;

    @Transactional
    public Outcome throwAt(Jwt jwt, UUID tableId, UUID targetId, String key) {
        UUID thrower = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        List<Boolean> canReact = jdbc.query("""
                SELECT m.can_react FROM table_members m JOIN tables t ON t.id = m.table_id
                WHERE m.table_id = ? AND m.user_id = ? AND t.archived_at IS NULL FOR SHARE OF m
                """, (rs, row) -> rs.getBoolean("can_react"), tableId, thrower);
        if (canReact.isEmpty()) return Outcome.NOT_FOUND;
        // A blocked target looks the same as someone who isn't here.
        Integer target = jdbc.queryForObject("""
                SELECT count(*) FROM table_members m JOIN users u ON u.id = m.user_id
                WHERE m.table_id = ? AND m.user_id = ? AND m.user_id <> ? AND u.status = 'ACTIVE' AND
                """ + NO_BLOCK, Integer.class, tableId, targetId, thrower, thrower, targetId, targetId, thrower);
        if (target == null || target == 0) return Outcome.NOT_FOUND;
        if (!canReact.getFirst()) return Outcome.FORBIDDEN;
        IdempotencyGuard.Claim claim = idempotency.claim(thrower, key, "tomato.throw.v1", tableId + "|" + targetId,
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM tomato_throws
                WHERE thrower_id = ? AND table_id = ? AND created_at > now() - make_interval(secs => ?)
                """, Integer.class, thrower, tableId, COOLDOWN_SECONDS);
        if (recent != null && recent > 0) {
            // Roll back the idempotency claim too, so the same key can be used once the cooldown passes.
            throw new CoolingDown();
        }
        jdbc.update("DELETE FROM tomato_throws WHERE table_id = ? AND created_at < now() - interval '1 day'",
                tableId);
        jdbc.update("INSERT INTO tomato_throws (id, table_id, thrower_id, target_id) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tableId, thrower, targetId);
        outbox.append(OutboxEvent.TABLE_TOMATO_THROWN, tableId, Map.of("tableId", tableId));
        authentication.requireUnexpired(jwt);
        return Outcome.CREATED;
    }

    /** Thrown within the cooldown; the transaction rolls back. */
    static final class CoolingDown extends RuntimeException {
        CoolingDown() {
            super(null, null, false, false);
        }
    }

    /**
     * Recent throws at a table the viewer is a current member of, without any involving someone they're in a block
     * relationship with.
     */
    public Read recent(Jwt jwt, UUID tableId) {
        UUID viewer = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        List<Boolean> access = jdbc.query("""
                SELECT EXISTS (SELECT 1 FROM table_members m JOIN tables t ON t.id = m.table_id
                               WHERE m.table_id = ? AND m.user_id = u.id AND t.archived_at IS NULL) AS member
                FROM auth_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                  AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                """, (rs, row) -> rs.getBoolean("member"), tableId, sessionId, viewer);
        if (access.isEmpty()) return new Read(false, null);
        if (!access.getFirst()) return new Read(true, null);
        List<Throw> throwsSeen = jdbc.query("""
                SELECT t.id, t.thrower_id, t.target_id, t.created_at FROM tomato_throws t
                WHERE t.table_id = ? AND t.created_at > now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM blocks b
                                  WHERE (b.blocker_id = ? AND b.blocked_id IN (t.thrower_id, t.target_id))
                                     OR (b.blocked_id = ? AND b.blocker_id IN (t.thrower_id, t.target_id)))
                ORDER BY t.created_at, t.id
                """, (rs, row) -> new Throw(rs.getObject("id", UUID.class), rs.getObject("thrower_id", UUID.class),
                rs.getObject("target_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class)),
                tableId, RECENT_SECONDS, viewer, viewer);
        return new Read(true, throwsSeen);
    }

    /** {@code throwsSeen} is null when the viewer isn't a current member of the table. */
    record Read(boolean authenticated, List<Throw> throwsSeen) {}
}
