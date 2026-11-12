package com.p2picks.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserSearchController {
    private static final int PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;

    UserSearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/users")
    ResponseEntity<?> search(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> parameters) {
        if (!parameters.containsKey("query") || parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.keySet().stream().anyMatch(key -> !List.of("query", "cursor").contains(key))) {
            return error(400, "bad_request", "Provide a valid query and cursor.");
        }
        String query = PostgresText.trim(parameters.getFirst("query")).toLowerCase(Locale.ROOT);
        if (!query.matches("[a-z0-9_]{1,15}")) return error(400, "bad_request", "Provide a valid query and cursor.");
        String after = "";
        if (parameters.containsKey("cursor")) {
            try {
                String cursor = parameters.getFirst("cursor");
                if (cursor.length() > 20) throw new IllegalArgumentException();
                byte[] bytes = Base64.getUrlDecoder().decode(cursor);
                after = new String(bytes, StandardCharsets.US_ASCII);
                if (!after.matches("[a-z0-9_]{3,15}")
                        || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                return error(400, "bad_request", "Provide a valid query and cursor.");
            }
        }
        List<UserRow> rows = jdbc.query("""
                SELECT found.id, found.username, found.hue
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN LATERAL (
                    SELECT u.id, u.username::text AS username, u.hue
                    FROM users u
                    WHERE u.id <> viewer.id AND u.status <> 'DELETED'
                      AND strpos(u.username::text, ?) > 0
                      AND u.username::text COLLATE "C" > ? COLLATE "C"
                      AND NOT EXISTS (
                          SELECT 1 FROM blocks b
                          WHERE (b.blocker_id = viewer.id AND b.blocked_id = u.id)
                             OR (b.blocker_id = u.id AND b.blocked_id = viewer.id))
                    ORDER BY u.username::text COLLATE "C"
                    LIMIT 51
                ) found ON true
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                ORDER BY found.username COLLATE "C"
                """, (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new UserRow(id == null ? null : new User(id, rs.getString("username"), rs.getInt("hue")));
                }, query, after, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (rows.isEmpty()) return error(401, "unauthorized", "Authentication required or credentials invalid.");
        List<User> users = new ArrayList<>();
        for (UserRow row : rows) if (row.user() != null) users.add(row.user());
        String nextCursor = null;
        if (users.size() > PAGE_SIZE) {
            users.removeLast();
            nextCursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(users.getLast().username().getBytes(StandardCharsets.US_ASCII));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(List.copyOf(users), nextCursor));
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }

    private record User(UUID id, String username, int hue) {}
    private record UserRow(User user) {}
    private record Page(List<User> items, String nextCursor) {}
}
