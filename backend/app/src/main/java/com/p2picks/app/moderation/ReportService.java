package com.p2picks.app.moderation;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReportService {
    /** Reports one player may file per rolling day; more is far beyond honest use. */
    static final int DAILY_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;

    ReportService(JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
    }

    enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, INVALID, RATE_LIMITED }

    record Submission(UUID targetId, String reason, String note, UUID tableId, UUID messageId) {
        String canonical() {
            return targetId + "|" + reason + "|" + note.length() + ":" + note + "|" + tableId + "|" + messageId;
        }
    }

    @Transactional
    public Outcome submit(Jwt jwt, String key, Submission report) {
        UUID reporter = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        if (report.targetId().equals(reporter)) return Outcome.INVALID;
        Integer target = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id = ? AND role = 'PLAYER'", Integer.class, report.targetId());
        if (target == null || target == 0) return Outcome.INVALID;
        String snapshot = null;
        if (report.tableId() != null) {
            // You can only attach a table you're at, and only a message the reported person wrote there.
            Integer member = jdbc.queryForObject("""
                    SELECT count(*) FROM table_members m JOIN tables t ON t.id = m.table_id
                    WHERE m.table_id = ? AND m.user_id = ? AND t.archived_at IS NULL
                    """, Integer.class, report.tableId(), reporter);
            if (member == null || member == 0) return Outcome.INVALID;
        }
        if (report.messageId() != null) {
            List<String> content = jdbc.query("""
                    SELECT CASE kind WHEN 'gif' THEN 'GIF ' || gif_id ELSE text END AS content
                    FROM chat_messages
                    WHERE id = ? AND table_id = ? AND author_id = ? AND kind IN ('text', 'gif')
                    """, (rs, row) -> rs.getString("content"), report.messageId(), report.tableId(),
                    report.targetId());
            if (content.isEmpty()) return Outcome.INVALID;
            snapshot = content.getFirst();
        }
        Integer recent = jdbc.queryForObject("""
                SELECT count(*) FROM reports WHERE reporter_id = ? AND created_at > now() - interval '1 day'
                """, Integer.class, reporter);
        if (recent != null && recent >= DAILY_LIMIT) return Outcome.RATE_LIMITED;
        IdempotencyGuard.Claim claim = idempotency.claim(reporter, key, "report.submit.v1", report.canonical(),
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        jdbc.update("""
                INSERT INTO reports (id, reporter_id, target_id, reason, note, table_id, message_id, message_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), reporter, report.targetId(), report.reason(), report.note(), report.tableId(),
                report.messageId(), snapshot);
        authentication.requireUnexpired(jwt);
        return Outcome.CREATED;
    }
}
