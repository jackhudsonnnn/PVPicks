package com.p2picks.app.tables;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableReadController {
    private final JdbcTemplate jdbc;

    TableReadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** How many member ids a summary carries, for its avatars; {@code memberCount} has the rest. */
    static final int MEMBER_PREVIEW = 4;

    /**
     * Your tables as summaries, by most recent activity (the later of the last bet and when you joined), newest
     * first. Summaries carry what lists need (name, a few members, your own role and permissions), never whole
     * member lists: someone at 100 tables of 500 would otherwise download 50,000 member ids. GET /tables/{id} has
     * the full table.
     */
    @GetMapping("/tables")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        List<Boolean> authenticated = new ArrayList<>();
        List<TableSummary> tables = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, t.name, t.emoji, me.role, me.joined_at,
                       me.can_bet, me.can_text, me.can_react, me.can_send_gifs, me.can_poll,
                       (SELECT max(b.created_at) FROM bets b WHERE b.table_id = t.id) AS last_bet_at,
                       (SELECT count(*) FROM table_members c WHERE c.table_id = t.id) AS member_count,
                       ARRAY(SELECT p.user_id::text FROM table_members p WHERE p.table_id = t.id
                             ORDER BY p.joined_at, p.user_id LIMIT ?) AS member_preview
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN table_members me ON me.user_id = viewer.id
                LEFT JOIN tables t ON t.id = me.table_id AND t.archived_at IS NULL
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                ORDER BY greatest((SELECT max(b.created_at) FROM bets b WHERE b.table_id = t.id), me.joined_at)
                         DESC NULLS LAST, t.id
                """, rs -> {
                    authenticated.add(true);
                    UUID id = rs.getObject("id", UUID.class);
                    if (id == null) return;
                    List<String> off = new ArrayList<>();
                    if (!rs.getBoolean("can_bet")) off.add("bets");
                    if (!rs.getBoolean("can_text")) off.add("text");
                    if (!rs.getBoolean("can_react")) off.add("react");
                    if (!rs.getBoolean("can_send_gifs")) off.add("gifs");
                    if (!rs.getBoolean("can_poll")) off.add("polls");
                    Timestamp lastBet = rs.getTimestamp("last_bet_at");
                    tables.add(new TableSummary(id, rs.getString("name"), rs.getString("emoji"),
                            rs.getString("role").equals("HOST"), rs.getInt("member_count"),
                            Arrays.stream((String[]) rs.getArray("member_preview").getArray())
                                    .map(UUID::fromString).toList(),
                            List.copyOf(off), lastBet == null ? null : lastBet.toInstant(),
                            rs.getTimestamp("joined_at").toInstant()));
                }, MEMBER_PREVIEW, sessionId, userId);
        if (authenticated.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tables);
    }

    static final int MEMBER_SEARCH_PAGE = 50;

    /**
     * Members of one of your tables whose username contains {@code q} (1 to 15 of a–z, 0–9 and _), by username, 50
     * at a time; {@code cursor} is the last username of the previous page. Lets a 500-person table be searched
     * without loading every member's profile. Same access as the table.
     */
    @GetMapping("/tables/{tableId}/members")
    ResponseEntity<?> searchMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestParam(name = "q") String query, @RequestParam(name = "cursor", required = false) String cursor) {
        String term = query.trim().toLowerCase();
        if (!term.matches("[a-z0-9_]{1,15}") || (cursor != null && !cursor.matches("[a-z0-9_]{1,15}"))) {
            return error(400, "bad_request", "Search with 1 to 15 letters, numbers or underscores.");
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        List<Boolean> viewer = jdbc.query("""
                SELECT me.user_id IS NOT NULL AS member FROM auth_sessions session
                JOIN users u ON u.id = session.user_id
                LEFT JOIN table_members me ON me.user_id = u.id AND me.table_id = ?
                    AND EXISTS (SELECT 1 FROM tables t WHERE t.id = me.table_id AND t.archived_at IS NULL)
                WHERE session.id = ? AND u.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND u.status = 'ACTIVE'
                """, (rs, row) -> rs.getBoolean("member"), tableId, UUID.fromString(jwt.getId()), userId);
        if (viewer.isEmpty()) return unauthorized();
        if (!viewer.getFirst()) return error(404, "not_found", "Table not found.");
        record Match(UUID id, String username) {}
        List<Match> matches = jdbc.query("""
                SELECT u.id, u.username::text AS username FROM table_members m JOIN users u ON u.id = m.user_id
                WHERE m.table_id = ? AND u.username IS NOT NULL
                  AND position(? IN lower(u.username::text)) > 0
                  AND (?::text IS NULL OR u.username::text > ?::text)
                ORDER BY u.username::text LIMIT ?
                """, (rs, row) -> new Match(rs.getObject("id", UUID.class), rs.getString("username")),
                tableId, term, cursor, cursor, MEMBER_SEARCH_PAGE + 1);
        boolean more = matches.size() > MEMBER_SEARCH_PAGE;
        List<Match> page = more ? matches.subList(0, MEMBER_SEARCH_PAGE) : matches;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.stream().map(Match::id).toList());
        body.put("nextCursor", more ? page.getLast().username() : null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** One of your tables in a list. */
    record TableSummary(UUID id, String name, String emoji, boolean host, int memberCount, List<UUID> memberPreview,
            List<String> myDisabledPermissions,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant lastBetAt, Instant joinedAt) {}

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
                       t.u2pick_enabled, t.polls_enabled, t.quickpick_code, t.quickpicks_enabled, t.quickpick_sharing,
                       me.role AS viewer_role, member.user_id AS member_id, member.role AS member_role,
                       member.can_bet, member.can_text, member.can_react, member.can_send_gifs, member.can_poll,
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
            String remainderRule, boolean hostsOnlyBets, boolean u2pickEnabled, boolean pollsEnabled,
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
        private final boolean pollsEnabled;
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
            pollsEnabled = rs.getBoolean("polls_enabled");
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
                if (!rs.getBoolean("can_poll")) disabled.add("polls");
                if (!disabled.isEmpty()) disabledPermissions.put(memberId.toString(), List.copyOf(disabled));
            }
        }

        private TableView build() {
            if (hostIds.isEmpty()) {
                throw new IllegalStateException("Table has no host: " + id);
            }
            return new TableView(id, name, emoji, List.copyOf(memberIds), List.copyOf(hostIds),
                    remainderRule, hostsOnlyBets, u2pickEnabled, pollsEnabled, quickPickCode, quickPicksEnabled,
                    quickPickSharing, subscribedTo, Map.copyOf(disabledPermissions));
        }
    }
}
