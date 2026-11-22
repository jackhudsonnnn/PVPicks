package com.p2picks.app.lan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /admin/lan/party} (admin only, under {@code /admin/**}): the party table for the admin's Users tab, or
 * {@code {"table": null}} before the first player. {@code deleted} is true once its host deleted it.
 */
@RestController
@ConditionalOnProperty(name = "pvpicks.lan.party.enabled", havingValue = "true")
class PartyController {
    private final JdbcTemplate jdbc;

    PartyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/lan/party")
    ResponseEntity<Map<String, Object>> party() {
        List<Map<String, Object>> tables = jdbc.query("""
                SELECT t.id, t.name, t.emoji, t.archived_at IS NOT NULL AS deleted
                FROM lan_party p JOIN tables t ON t.id = p.table_id
                """, (rs, row) -> {
                    Map<String, Object> table = new LinkedHashMap<>();
                    table.put("id", rs.getObject("id", UUID.class));
                    table.put("name", rs.getString("name"));
                    table.put("emoji", rs.getString("emoji"));
                    table.put("deleted", rs.getBoolean("deleted"));
                    return table;
                });
        Map<String, Object> body = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            body.put("table", null);
        } else {
            Map<String, Object> table = tables.getFirst();
            table.put("hostIds", jdbc.queryForList("""
                    SELECT user_id FROM table_members WHERE table_id = ? AND role = 'HOST' ORDER BY joined_at
                    """, UUID.class, table.get("id")));
            table.put("memberIds", jdbc.queryForList("""
                    SELECT user_id FROM table_members WHERE table_id = ? ORDER BY joined_at
                    """, UUID.class, table.get("id")));
            body.put("table", table);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
