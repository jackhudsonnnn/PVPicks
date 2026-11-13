package com.p2picks.app.users;

import static com.p2picks.app.common.ApiErrors.error;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserLookupController {
    private final JdbcTemplate jdbc;

    UserLookupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users/{userId}")
    ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        List<Lookup> rows = jdbc.query("""
                WITH viewer AS MATERIALIZED (
                    SELECT u.id FROM auth_sessions session JOIN users u ON u.id = session.user_id
                    WHERE session.id = ? AND u.id = ? AND session.revoked_at IS NULL
                      AND session.expires_at > now() AND u.status = 'ACTIVE'
                ), target AS MATERIALIZED (
                    SELECT u.* FROM users u WHERE u.id = ?
                ), accessible_tables AS MATERIALIZED (
                    SELECT t.id, t.archived_at
                    FROM viewer JOIN table_members m ON m.user_id = viewer.id
                    JOIN tables t ON t.id = m.table_id AND t.archived_at IS NULL
                    UNION ALL
                    SELECT t.id, t.archived_at
                    FROM viewer JOIN table_archive_members m ON m.user_id = viewer.id
                    JOIN tables t ON t.id = m.table_id AND t.archived_at IS NOT NULL
                        AND t.archived_at + interval '2160 hours' > now()
                ), context AS (
                    SELECT
                      EXISTS (SELECT 1 FROM accessible_tables t JOIN table_members m ON m.table_id = t.id
                              JOIN target ON target.id = m.user_id WHERE t.archived_at IS NULL) AS shared_current,
                      EXISTS (SELECT 1 FROM settlement_lines line CROSS JOIN viewer CROSS JOIN target
                              WHERE (line.payer_id = viewer.id AND line.payee_id = target.id)
                                 OR (line.payee_id = viewer.id AND line.payer_id = target.id)) AS receipt,
                      EXISTS (SELECT 1 FROM accessible_tables t JOIN table_archive_members m ON m.table_id = t.id
                              JOIN target ON target.id = m.user_id WHERE t.archived_at IS NOT NULL) AS shared_archive,
                      EXISTS (
                          SELECT 1 FROM accessible_tables t JOIN chat_messages m ON m.table_id = t.id
                          CROSS JOIN target
                          WHERE (m.kind = 'bet' AND m.author_id = target.id)
                             OR (m.kind = 'system' AND m.system_subject_id = target.id)
                             OR (m.kind = 'settlement' AND (
                                 EXISTS (SELECT 1 FROM settlements s
                                         WHERE s.id = m.settlement_id AND s.host_id = target.id)
                                 OR EXISTS (SELECT 1 FROM settlement_lines line
                                            WHERE line.settlement_id = m.settlement_id
                                              AND (line.payer_id = target.id OR line.payee_id = target.id))))
                      ) AS historical_chat
                )
                SELECT target.id, target.username::text, target.hue, target.status
                FROM viewer CROSS JOIN context
                LEFT JOIN target ON (
                    (target.status = 'DELETED' AND
                        (context.shared_current OR context.shared_archive OR context.receipt OR context.historical_chat))
                    OR (target.status <> 'DELETED' AND (
                        target.id = viewer.id OR context.shared_current OR context.receipt
                        OR EXISTS (SELECT 1 FROM blocks b
                                   WHERE b.blocker_id = viewer.id AND b.blocked_id = target.id)
                        OR NOT EXISTS (SELECT 1 FROM blocks b
                                       WHERE b.blocker_id = target.id AND b.blocked_id = viewer.id)))
                )
                """, (rs, row) -> new Lookup(rs.getObject("id", UUID.class),
                        rs.getString("username"), rs.getObject("hue", Integer.class), rs.getString("status")),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()), userId);
        if (rows.isEmpty()) return error(401, "unauthorized", "Authentication required or credentials invalid.");
        Lookup result = rows.getFirst();
        if (result.id() == null) return error(404, "not_found", "User not found.");
        if ("DELETED".equals(result.status())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new DeletedUser(result.id(), "Deleted user", true));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new User(result.id(), result.username(), result.hue()));
    }


    private record Lookup(UUID id, String username, Integer hue, String status) {}
    private record User(UUID id, String username, int hue) {}
    private record DeletedUser(UUID id, String displayName, boolean deleted) {}
}
