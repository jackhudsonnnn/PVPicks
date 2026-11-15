package com.p2picks.app.demo;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.security.PasswordlessSignIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo's sign-in picker and the Dev page's reset. These paths 404 unless the demo is on (dev profile only).
 * The picker endpoints are open: in the demo, anyone who can reach the dev backend may be anyone.
 */
@RestController
@ConditionalOnProperty(name = "pvpicks.demo.enabled", havingValue = "true")
class DemoController {
    private final JdbcTemplate jdbc;
    private final PasswordlessSignIn signIn;
    private final DemoReset reset;
    private final org.springframework.beans.factory.ObjectProvider<DemoBots> bots;

    DemoController(JdbcTemplate jdbc, PasswordlessSignIn signIn, DemoReset reset,
            org.springframework.beans.factory.ObjectProvider<DemoBots> bots) {
        this.jdbc = jdbc;
        this.signIn = signIn;
        this.reset = reset;
        this.bots = bots;
    }

    /** The main accounts, then demo users matching {@code q} (by username prefix), at most 20. */
    @GetMapping("/dev/demo/accounts")
    ResponseEntity<?> accounts(@RequestParam(name = "q", defaultValue = "") String q) {
        String query = q.trim().toLowerCase();
        if (!query.matches("[a-z0-9_]{0,15}")) return error(400, "bad_request", "Search by username.");
        var row = (org.springframework.jdbc.core.RowMapper<Map<String, Object>>) (rs, i) -> {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("id", rs.getObject("id", UUID.class));
            account.put("username", rs.getString("username"));
            account.put("hue", rs.getInt("hue"));
            account.put("role", rs.getString("role"));
            account.put("frozen", rs.getString("status").equals("FROZEN"));
            return account;
        };
        String columns = "SELECT id, username::text AS username, hue, role, status FROM users ";
        List<Map<String, Object>> main = jdbc.query(columns + """
                WHERE username::text = ANY (?) AND status IN ('ACTIVE', 'FROZEN')
                ORDER BY array_position(?, username::text)
                """, row, DemoRoster.MAIN.toArray(String[]::new), DemoRoster.MAIN.toArray(String[]::new));
        List<Map<String, Object>> others = jdbc.query(columns + """
                WHERE status IN ('ACTIVE', 'FROZEN') AND NOT (username::text = ANY (?))
                  AND username::text LIKE ? || '%'
                ORDER BY username LIMIT 20
                """, row, DemoRoster.MAIN.toArray(String[]::new), query);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("main", main, "others", others));
    }

    @PostMapping("/dev/demo/sign-in")
    ResponseEntity<?> signIn(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        UUID userId;
        try {
            userId = UUID.fromString(input.path("userId").asText(""));
        } catch (IllegalArgumentException invalid) {
            return error(400, "bad_request", "userId is required.");
        }
        return switch (signIn.signIn(userId, request, response)) {
            case SIGNED_IN -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case FROZEN -> error(403, "account_frozen", "This account is frozen.");
            case NO_SUCH_ACCOUNT -> error(404, "not_found", "No such account.");
        };
    }

    /** What the demo world holds now, and what the bots have done; the Dev page shows its section when this answers. */
    @GetMapping("/dev/demo")
    ResponseEntity<?> status() {
        DemoReset.Summary world = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM users WHERE status = 'ACTIVE') AS users,
                       (SELECT count(*) FROM follows) AS follows,
                       (SELECT count(*) FROM blocks) AS blocks,
                       (SELECT count(*) FROM tables WHERE archived_at IS NULL) AS tables
                """, (rs, row) -> new DemoReset.Summary(rs.getInt("users"), rs.getInt("follows"),
                        rs.getInt("blocks"), rs.getInt("tables")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", world.users());
        body.put("follows", world.follows());
        body.put("blocks", world.blocks());
        body.put("tables", world.tables());
        DemoBots running = bots.getIfAvailable();
        body.put("bots", running == null ? null : running.status());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** Wipes app data and reseeds the demo world; every session ends, the caller's included. */
    @PostMapping("/dev/demo/reset")
    ResponseEntity<?> reset(HttpServletRequest request, HttpServletResponse response) {
        DemoReset.Summary summary = reset.reset();
        signIn.signOut(request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(summary);
    }
}
