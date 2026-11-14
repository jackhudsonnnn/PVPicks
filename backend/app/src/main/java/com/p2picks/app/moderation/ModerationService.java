package com.p2picks.app.moderation;

import com.p2picks.app.accounts.AccountStatus;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin moderation queue. Each report is dismissed, or resolved with a strike against the reported player.
 * Strikes stop at three; reaching three (again, after an unfreeze) freezes the account. Clearing strikes doesn't
 * unfreeze, and unfreezing doesn't clear strikes. Admin endpoints are admin-only by the security config.
 */
@Service
class ModerationService {
    static final int STRIKES_TO_FREEZE = 3;

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, NOT_FOUND, ALREADY_HANDLED }

    record Person(UUID id, String username, String status, int strikes) {}
    record ReportView(UUID id, String reason, String note, String status, OffsetDateTime createdAt, Person reporter,
                      Person target, UUID tableId, String tableName, String messageSnapshot) {}
    record StrikeResult(Outcome outcome, int strikes, boolean frozen) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final AccountStatus accounts;

    ModerationService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            AccountStatus accounts) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.accounts = accounts;
    }

    /** Reports newest first; open ones only unless `all`. */
    List<ReportView> reports(boolean all) {
        return jdbc.query("""
                SELECT r.id, r.reason, r.note, r.status, r.created_at, r.table_id, t.name AS table_name,
                       r.message_snapshot,
                       r.reporter_id, rep.username::text AS reporter_name, rep.status AS reporter_status,
                       COALESCE(rf.strike_count, 0) AS reporter_strikes,
                       r.target_id, tgt.username::text AS target_name, tgt.status AS target_status,
                       COALESCE(tf.strike_count, 0) AS target_strikes
                FROM reports r
                JOIN users rep ON rep.id = r.reporter_id
                JOIN users tgt ON tgt.id = r.target_id
                LEFT JOIN admin_user_flags rf ON rf.user_id = r.reporter_id
                LEFT JOIN admin_user_flags tf ON tf.user_id = r.target_id
                LEFT JOIN tables t ON t.id = r.table_id
                WHERE ? OR r.status = 'OPEN'
                ORDER BY r.created_at DESC, r.id
                LIMIT 200
                """, (rs, row) -> new ReportView(rs.getObject("id", UUID.class), rs.getString("reason"),
                        rs.getString("note"), rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        new Person(rs.getObject("reporter_id", UUID.class), rs.getString("reporter_name"),
                                rs.getString("reporter_status"), rs.getInt("reporter_strikes")),
                        new Person(rs.getObject("target_id", UUID.class), rs.getString("target_name"),
                                rs.getString("target_status"), rs.getInt("target_strikes")),
                        rs.getObject("table_id", UUID.class), rs.getString("table_name"),
                        rs.getString("message_snapshot")), all);
    }

    /** Players with strikes or a frozen account, for the admin's people list. */
    List<Person> flagged() {
        return jdbc.query("""
                SELECT u.id, u.username::text AS username, u.status, COALESCE(f.strike_count, 0) AS strikes
                FROM users u LEFT JOIN admin_user_flags f ON f.user_id = u.id
                WHERE u.role = 'PLAYER' AND u.status <> 'DELETED' AND (u.status = 'FROZEN' OR f.strike_count > 0)
                ORDER BY u.status DESC, strikes DESC, u.username
                """, (rs, row) -> new Person(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("status"), rs.getInt("strikes")));
    }

    @Transactional
    public Outcome dismiss(Jwt jwt, UUID reportId, String key) {
        Outcome start = begin(jwt, key, "moderation.report.dismiss.v1", reportId.toString());
        if (start != null) return start;
        int changed = jdbc.update("UPDATE reports SET status = 'DISMISSED' WHERE id = ? AND status = 'OPEN'",
                reportId);
        if (changed == 1) return Outcome.UPDATED;
        return exists(reportId) ? Outcome.ALREADY_HANDLED : Outcome.NOT_FOUND;
    }

    @Transactional
    public StrikeResult strike(Jwt jwt, UUID reportId, String key) {
        Outcome start = begin(jwt, key, "moderation.report.strike.v1", reportId.toString());
        if (start != null) return new StrikeResult(start, 0, false);
        List<UUID> targets = jdbc.query("SELECT target_id FROM reports WHERE id = ? AND status = 'OPEN' FOR UPDATE",
                (rs, row) -> rs.getObject("target_id", UUID.class), reportId);
        if (targets.isEmpty()) {
            return new StrikeResult(exists(reportId) ? Outcome.ALREADY_HANDLED : Outcome.NOT_FOUND, 0, false);
        }
        UUID target = targets.getFirst();
        jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", rs -> { }, target);
        jdbc.update("UPDATE reports SET status = 'RESOLVED' WHERE id = ?", reportId);
        Integer strikes = jdbc.queryForObject("""
                INSERT INTO admin_user_flags (user_id, strike_count) VALUES (?, 1)
                ON CONFLICT (user_id) DO UPDATE SET strike_count = LEAST(admin_user_flags.strike_count + 1, ?)
                RETURNING strike_count
                """, Integer.class, target, STRIKES_TO_FREEZE);
        boolean frozen = strikes != null && strikes >= STRIKES_TO_FREEZE && accounts.freeze(target);
        return new StrikeResult(Outcome.UPDATED, strikes == null ? 0 : strikes, frozen);
    }

    @Transactional
    public Outcome unfreeze(Jwt jwt, UUID userId, String key) {
        Outcome start = begin(jwt, key, "moderation.user.unfreeze.v1", userId.toString());
        if (start != null) return start;
        if (!lockPlayer(userId)) return Outcome.NOT_FOUND;
        return accounts.unfreeze(userId) ? Outcome.UPDATED : Outcome.ALREADY_HANDLED;
    }

    @Transactional
    public Outcome clearStrikes(Jwt jwt, UUID userId, String key) {
        Outcome start = begin(jwt, key, "moderation.user.clear_strikes.v1", userId.toString());
        if (start != null) return start;
        if (!lockPlayer(userId)) return Outcome.NOT_FOUND;
        jdbc.update("UPDATE admin_user_flags SET strike_count = 0 WHERE user_id = ?", userId);
        return Outcome.UPDATED;
    }

    /** Locks the admin, claims the key; null to go on, or the outcome to return. */
    private Outcome begin(Jwt jwt, String key, String operation, String request) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        IdempotencyGuard.Claim claim = idempotency.claim(UUID.fromString(jwt.getSubject()), key, operation, request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        return null;
    }

    private boolean lockPlayer(UUID userId) {
        return !jdbc.query("SELECT id FROM users WHERE id = ? AND role = 'PLAYER' AND status <> 'DELETED' FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), userId).isEmpty();
    }

    private boolean exists(UUID reportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM reports WHERE id = ?)",
                Boolean.class, reportId));
    }
}
