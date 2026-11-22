package com.p2picks.app.feedback;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.PageCursor;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feedback inbox for admins: players' feedback newest first, {@value #PAGE} at a time, filtered by status and
 * kind; an admin marks an item Done (or New again) with an optional private note.
 */
@RestController
class AdminFeedbackController {
    static final int PAGE = 50;
    static final int NOTE_LIMIT = 500;
    private static final Set<String> KINDS = Set.of("mode", "perk", "gif", "bug", "other");

    record Item(UUID id, UUID userId, String username, Integer hue, String kind, String text, String link, String status,
            String adminNote, String handledBy, Instant handledAt, Instant createdAt) {}

    record Counts(int open, int done) {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final TransactionTemplate transactions;

    AdminFeedbackController(JdbcTemplate jdbc, MutationAuthentication authentication,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.transactions = transactions;
    }

    @GetMapping("/admin/feedback")
    ResponseEntity<?> list(@RequestParam(defaultValue = "new") String status,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String cursor) {
        if (!Set.of("new", "done", "all").contains(status)) return error(400, "bad_request", "status is new, done or all.");
        if (kind != null && !KINDS.contains(kind)) return error(400, "bad_request", "Unknown kind.");
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
        String wanted = status.equals("all") ? null : status.toUpperCase();
        List<Item> items = new ArrayList<>(jdbc.query("""
                SELECT f.id, f.user_id, u.username::text AS username, u.hue, f.kind, f.text, f.link, f.status, f.admin_note,
                       h.username::text AS handled_by, f.handled_at, f.created_at
                FROM feedback f JOIN users u ON u.id = f.user_id LEFT JOIN users h ON h.id = f.handled_by
                WHERE (?::text IS NULL OR f.status = ?) AND (?::text IS NULL OR f.kind = ?)
                  AND (?::timestamptz IS NULL OR (f.created_at, f.id) < (?::timestamptz, ?::uuid))
                ORDER BY f.created_at DESC, f.id DESC LIMIT ?
                """, (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("username"), (Integer) rs.getObject("hue", Integer.class), rs.getString("kind"), rs.getString("text"), rs.getString("link"),
                        rs.getString("status"), rs.getString("admin_note"), rs.getString("handled_by"),
                        instant(rs.getTimestamp("handled_at")), instant(rs.getTimestamp("created_at"))),
                wanted, wanted, kind, kind, before, before, beforeId, PAGE + 1));
        String next = null;
        if (items.size() > PAGE) {
            items = new ArrayList<>(items.subList(0, PAGE));
            Item last = items.getLast();
            next = PageCursor.encode(last.createdAt().toString(), last.id());
        }
        Counts counts = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'NEW') AS open, count(*) FILTER (WHERE status = 'DONE') AS done
                FROM feedback
                """, (rs, row) -> new Counts(rs.getInt("open"), rs.getInt("done")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", next);
        body.put("counts", counts);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Sets the item's status and note ({"status": "NEW" | "DONE", "note": string | null}). Repeating it is harmless. */
    @PutMapping("/admin/feedback/{feedbackId}")
    ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID feedbackId,
            @RequestBody JsonNode input) {
        if (!input.isObject() || !input.path("status").isTextual()
                || !Set.of("NEW", "DONE").contains(input.path("status").textValue())
                || input.has("note") && !input.path("note").isNull() && !input.path("note").isTextual()) {
            return error(400, "bad_request", "Send a status of NEW or DONE and an optional note.");
        }
        String note = input.path("note").isTextual() ? PostgresText.trim(input.path("note").textValue()) : "";
        if (note.length() > NOTE_LIMIT || !PostgresText.valid(note)) {
            return error(400, "bad_request", "Notes are up to " + NOTE_LIMIT + " characters.");
        }
        String status = input.path("status").textValue();
        try {
            Integer updated = transactions.execute(tx -> {
                if (!authentication.lockActive(jwt)) return null;
                int rows = jdbc.update("""
                        UPDATE feedback SET status = ?, admin_note = ?,
                            handled_by = CASE WHEN ? = 'DONE' THEN ?::uuid END,
                            handled_at = CASE WHEN ? = 'DONE' THEN COALESCE(
                                CASE WHEN status = 'DONE' THEN handled_at END, now()) END
                        WHERE id = ?
                        """, status, note.isEmpty() ? null : note, status, UUID.fromString(jwt.getSubject()), status,
                        feedbackId);
                authentication.requireUnexpired(jwt);
                return rows;
            });
            if (updated == null) return error(401, "unauthorized", "Authentication required or credentials invalid.");
            if (updated == 0) return error(404, "not_found", "Not found.");
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
