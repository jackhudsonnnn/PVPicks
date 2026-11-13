package com.p2picks.app.tables;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class TableReadController {
    private final JdbcTemplate jdbc;

    TableReadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/tables")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        ReadResult result = read(jwt, null);
        if (!result.authenticated()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.tables());
    }

    @GetMapping("/tables/{tableId}")
    ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        ReadResult result = read(jwt, tableId);
        if (!result.authenticated()) {
            return unauthorized();
        }
        if (result.tables().isEmpty()) {
            return error(404, "not_found", "Table not found.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.tables().getFirst());
    }

    private ReadResult read(Jwt jwt, UUID tableId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        return jdbc.query("""
                SELECT t.id, t.name, t.emoji, t.remainder_rule, t.hosts_only_bets,
                       t.u2pick_enabled, t.quickpick_code, t.quickpicks_enabled, t.quickpick_sharing,
                       me.role AS viewer_role, member.user_id AS member_id, member.role AS member_role,
                       member.can_bet, member.can_text, member.can_react, member.can_send_gifs,
                       ARRAY(SELECT q.source_table_id::text FROM quickpick_subscriptions q
                             WHERE q.subscriber_table_id = t.id ORDER BY q.source_table_id) AS subscriptions
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN table_members me ON me.user_id = viewer.id
                    AND (?::uuid IS NULL OR me.table_id = ?::uuid)
                LEFT JOIN tables t ON t.id = me.table_id AND t.archived_at IS NULL
                LEFT JOIN table_members member ON member.table_id = t.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                ORDER BY t.created_at, t.id, member.joined_at, member.user_id
                """, rs -> {
                    return extract(rs, userId);
                }, tableId, tableId, sessionId, userId);
    }

    private static ReadResult extract(ResultSet rs, UUID userId) throws SQLException {
        boolean authenticated = false;
        Map<UUID, TableBuilder> tables = new LinkedHashMap<>();
        while (rs.next()) {
            authenticated = true;
            UUID tableId = rs.getObject("id", UUID.class);
            if (tableId == null) {
                continue;
            }
            TableBuilder table = tables.get(tableId);
            if (table == null) {
                table = new TableBuilder(rs);
                tables.put(tableId, table);
            }
            table.addMember(rs, userId);
        }
        return new ReadResult(authenticated, tables.values().stream().map(TableBuilder::build).toList());
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private record ReadResult(boolean authenticated, List<TableView> tables) {}

    private record TableView(
            UUID id, String name, String emoji, List<UUID> memberIds, List<UUID> hostIds,
            String remainderRule, boolean hostsOnlyBets, boolean u2pickEnabled,
            @JsonInclude(JsonInclude.Include.NON_NULL) String quickPickCode,
            boolean quickPicksEnabled, boolean quickPickSharing, List<UUID> subscribedTo,
            Map<String, List<String>> disabledPermissions) {}

    private static final class TableBuilder {
        private final UUID id;
        private final String name;
        private final String emoji;
        private final String remainderRule;
        private final boolean hostsOnlyBets;
        private final boolean u2pickEnabled;
        private final String quickPickCode;
        private final boolean quickPicksEnabled;
        private final boolean quickPickSharing;
        private final boolean viewerIsHost;
        private final List<UUID> subscribedTo;
        private final List<UUID> memberIds = new ArrayList<>();
        private final List<UUID> hostIds = new ArrayList<>();
        private final Map<String, List<String>> disabledPermissions = new LinkedHashMap<>();

        private TableBuilder(ResultSet rs) throws SQLException {
            id = rs.getObject("id", UUID.class);
            name = rs.getString("name");
            emoji = rs.getString("emoji");
            remainderRule = rs.getString("remainder_rule");
            hostsOnlyBets = rs.getBoolean("hosts_only_bets");
            u2pickEnabled = rs.getBoolean("u2pick_enabled");
            viewerIsHost = rs.getString("viewer_role").equals("HOST");
            quickPickCode = viewerIsHost ? rs.getString("quickpick_code") : null;
            quickPicksEnabled = rs.getBoolean("quickpicks_enabled");
            quickPickSharing = rs.getBoolean("quickpick_sharing");
            subscribedTo = Arrays.stream((String[]) rs.getArray("subscriptions").getArray())
                    .map(UUID::fromString).toList();
        }

        private void addMember(ResultSet rs, UUID viewerId) throws SQLException {
            UUID memberId = rs.getObject("member_id", UUID.class);
            memberIds.add(memberId);
            if (rs.getString("member_role").equals("HOST")) {
                hostIds.add(memberId);
            }
            if (viewerIsHost || memberId.equals(viewerId)) {
                List<String> disabled = new ArrayList<>();
                if (!rs.getBoolean("can_bet")) disabled.add("bets");
                if (!rs.getBoolean("can_text")) disabled.add("text");
                if (!rs.getBoolean("can_react")) disabled.add("react");
                if (!rs.getBoolean("can_send_gifs")) disabled.add("gifs");
                if (!disabled.isEmpty()) disabledPermissions.put(memberId.toString(), List.copyOf(disabled));
            }
        }

        private TableView build() {
            if (hostIds.isEmpty()) {
                throw new IllegalStateException("Table has no host: " + id);
            }
            return new TableView(id, name, emoji, List.copyOf(memberIds), List.copyOf(hostIds),
                    remainderRule, hostsOnlyBets, u2pickEnabled, quickPickCode, quickPicksEnabled,
                    quickPickSharing, subscribedTo, Map.copyOf(disabledPermissions));
        }
    }
}
