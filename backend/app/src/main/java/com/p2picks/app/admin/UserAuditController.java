package com.p2picks.app.admin;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.common.PageCursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A player's audit and their writing, for admins. Opening an audit and searching inside one are recorded in
 * {@code admin_audit_log} (once per admin, player and search per {@link #LOG_QUIET_MINUTES} minutes, so
 * refreshes don't flood it); {@code GET /admin/audit-log} lists the log, for everyone or
 * one player ({@code subject}).
 */
@RestController
class UserAuditController {
    static final int LOG_QUIET_MINUTES = 10;
    static final int LOG_PAGE = 50;
    private static final Set<String> KINDS = Set.of("message", "poll", "bet");

    record LogEntry(UUID id, UUID adminId, String adminName, Integer adminHue, String action, UUID subjectId,
            String subjectName, Integer subjectHue,
            String query, Instant at) {}

    private final UserAudit audits;
    private final JdbcTemplate jdbc;

    UserAuditController(UserAudit audits, JdbcTemplate jdbc) {
        this.audits = audits;
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/users/{userId}/audit")
    ResponseEntity<?> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        if (!audits.exists(userId)) return error(404, "not_found", "No such player.");
        // Logged before the audit loads, so its "Audited by" list (the log filtered to this player) includes this view.
        log(jwt, "view_user", userId, null);
        var audit = audits.audit(userId);
        if (audit.isEmpty()) return error(404, "not_found", "No such player.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(audit.get());
    }

    @GetMapping("/admin/users/{userId}/activity")
    ResponseEntity<?> activity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor) {
        if (kind != null && !KINDS.contains(kind)) return error(400, "bad_request", "kind is message, poll or bet.");
        if (q != null && q.length() > 100) return error(400, "bad_request", "Search up to 100 characters.");
        if (!audits.exists(userId)) return error(404, "not_found", "No such player.");
        UserAudit.Activity page;
        try {
            page = audits.activity(userId, kind, q, cursor);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            return error(400, "bad_request", "Invalid cursor.");
        }
        if (q != null && !q.isBlank() && cursor == null) log(jwt, "search_activity", userId, q.trim());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/admin/audit-log")
    ResponseEntity<?> log(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID subject) {
        Timestamp before = null;
        UUID beforeId = null;
        try {
            if (cursor != null) {
                List<String> parts = PageCursor.decode(cursor, 2);
                before = Timestamp.from(OffsetDateTime.parse(parts.get(0)).toInstant());
                beforeId = UUID.fromString(parts.get(1));
            }
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            return error(400, "bad_request", "Invalid cursor.");
        }
        List<LogEntry> items = new ArrayList<>(jdbc.query("""
                SELECT l.id, l.admin_id, a.username::text AS admin_name, a.hue AS admin_hue, l.action, l.subject_id,
                       s.username::text AS subject_name, s.hue AS subject_hue, l.query, l.created_at
                FROM admin_audit_log l JOIN users a ON a.id = l.admin_id JOIN users s ON s.id = l.subject_id
                WHERE (?::uuid IS NULL OR l.subject_id = ?::uuid)
                  AND (?::timestamptz IS NULL OR (l.created_at, l.id) < (?::timestamptz, ?::uuid))
                ORDER BY l.created_at DESC, l.id DESC LIMIT ?
                """, (rs, row) -> new LogEntry(rs.getObject("id", UUID.class), rs.getObject("admin_id", UUID.class),
                        rs.getString("admin_name"), (Integer) rs.getObject("admin_hue", Integer.class), rs.getString("action"),
                        rs.getObject("subject_id", UUID.class), rs.getString("subject_name"), (Integer) rs.getObject("subject_hue", Integer.class), rs.getString("query"),
                        rs.getTimestamp("created_at").toInstant()), subject, subject, before, before, beforeId, LOG_PAGE + 1));
        String next = null;
        if (items.size() > LOG_PAGE) {
            items = new ArrayList<>(items.subList(0, LOG_PAGE));
            LogEntry last = items.getLast();
            next = PageCursor.encode(last.at().toString(), last.id());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", next);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Records an audit unless this admin recorded the same one in the last few minutes. */
    private void log(Jwt jwt, String action, UUID subjectId, String query) {
        UUID adminId = UUID.fromString(jwt.getSubject());
        jdbc.update("""
                INSERT INTO admin_audit_log (id, admin_id, action, subject_id, query)
                SELECT ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM admin_audit_log
                    WHERE admin_id = ? AND action = ? AND subject_id = ? AND query IS NOT DISTINCT FROM ?
                      AND created_at > now() - make_interval(mins => ?))
                """, UUID.randomUUID(), adminId, action, subjectId, query, adminId, action, subjectId, query,
                LOG_QUIET_MINUTES);
    }
}
