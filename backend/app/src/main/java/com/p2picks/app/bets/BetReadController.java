package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
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

@RestController
class BetReadController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    BetReadController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * The table's live bets, oldest first: Active, Pending, and resolved non-4fun bets whose points aren't settled
     * yet (the same set that blocks deleting the table). Older bets are read one at a time or per member.
     */
    @GetMapping("/tables/{tableId}/bets")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        ReadResult result = read(jwt, new Filter(tableId, null, null, true, null, null, null, null));
        if (!result.authenticated()) return unauthorized();
        if (!result.tableAccessible()) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.bets());
    }

    /**
     * Bets at a table that involve a member, newest first and paged: ones they proposed, and ones they joined once
     * picks are public (after lock). The viewer's own Active participation counts too.
     */
    @GetMapping("/tables/{tableId}/members/{userId}/bets")
    ResponseEntity<?> memberBets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
                                 @PathVariable UUID userId,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        int size = limit == null ? DEFAULT_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) return badRequest("limit must be between 1 and " + MAX_PAGE + ".");
        Cursor after;
        try {
            after = cursor == null ? null : Cursor.decode(cursor);
        } catch (IllegalArgumentException invalid) {
            return badRequest("Invalid cursor.");
        }
        ReadResult result = read(jwt, new Filter(tableId, null, null, false, userId,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), size + 1));
        if (!result.authenticated()) return unauthorized();
        if (!result.tableAccessible()) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page(result.bets(), size));
    }

    /** A member's record at a table: resolved, non-4fun bets they picked in, how many they won or lost, and the net. */
    @GetMapping("/tables/{tableId}/members/{userId}/record")
    ResponseEntity<?> record(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
                             @PathVariable UUID userId) {
        List<Map<String, Object>> rows = jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                ), results AS (
                    SELECT COALESCE((SELECT sum(le.delta_cents) FROM ledger_entries le
                                     WHERE le.bet_id = b.id AND le.user_id = ?), 0) AS delta
                    FROM bets b JOIN picks p ON p.bet_id = b.id AND p.user_id = ?
                    WHERE b.table_id = ? AND b.status = 'SETTLED' AND NOT b.is_4fun
                )
                SELECT EXISTS (SELECT 1 FROM table_members m JOIN tables t ON t.id = m.table_id
                               WHERE m.user_id = viewer.id AND t.id = ? AND t.archived_at IS NULL) AS accessible,
                       (SELECT count(*) FROM results) AS played,
                       (SELECT count(*) FROM results WHERE delta > 0) AS won,
                       (SELECT count(*) FROM results WHERE delta < 0) AS lost,
                       (SELECT COALESCE(sum(delta), 0) FROM results) AS net
                FROM viewer
                """, (rs, row) -> {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("accessible", rs.getBoolean("accessible"));
                    record.put("played", rs.getLong("played"));
                    record.put("won", rs.getLong("won"));
                    record.put("lost", rs.getLong("lost"));
                    record.put("netCents", rs.getLong("net"));
                    return record;
                }, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()), userId, userId, tableId,
                tableId);
        if (rows.isEmpty()) return unauthorized();
        Map<String, Object> record = rows.getFirst();
        if (!(Boolean) record.remove("accessible")) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(record);
    }

    @GetMapping("/bets/{betId}")
    ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID betId) {
        ReadResult result = read(jwt, null, betId);
        if (!result.authenticated()) return unauthorized();
        if (result.bets().isEmpty()) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.bets().getFirst());
    }

    private static final int DEFAULT_PAGE = 30;
    private static final int MAX_PAGE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "PENDING", "SETTLED", "WASHED");

    /**
     * The caller's bets across their current tables (or one of them), newest first. Uses the same projection as the
     * table list, so active picks stay private.
     */
    @GetMapping("/me/bets")
    ResponseEntity<?> mine(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) UUID tableId,
                           @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) Integer limit) {
        if (status != null && !STATUSES.contains(status)) return badRequest("Unknown bet status.");
        int size = limit == null ? DEFAULT_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) return badRequest("limit must be between 1 and " + MAX_PAGE + ".");
        Cursor after;
        try {
            after = cursor == null ? null : Cursor.decode(cursor);
        } catch (IllegalArgumentException invalid) {
            return badRequest("Invalid cursor.");
        }
        ReadResult result = read(jwt, new Filter(tableId, null, status, false, null,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), size + 1));
        if (!result.authenticated()) return unauthorized();
        if (tableId != null && !result.tableAccessible()) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page(result.bets(), size));
    }

    /** A BetPage from `size + 1` rows: the extra row only says there's more. */
    private static Map<String, Object> page(List<JsonNode> rows, int size) {
        List<JsonNode> items = rows.size() > size ? rows.subList(0, size) : rows;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", rows.size() > size ? Cursor.of(items.getLast()).encode() : null);
        return body;
    }

    /** Opaque page position: the last bet's creation time and id, base64url-encoded. */
    private record Cursor(OffsetDateTime createdAt, UUID id) {
        static Cursor of(JsonNode bet) {
            return new Cursor(OffsetDateTime.parse(bet.path("createdAt").asText()),
                    UUID.fromString(bet.path("id").asText()));
        }

        static Cursor decode(String value) {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("cursor");
            try {
                return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("cursor", invalid);
            }
        }

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }
    }

    private ReadResult read(Jwt jwt, UUID tableId, UUID betId) {
        return read(jwt, new Filter(tableId, betId, null, false, null, null, null, null));
    }

    /**
     * One projection for every bet read, so privacy rules (other picks hidden while Active) live in one place.
     * Without a page limit, bets come oldest first; with one, newest first after the cursor.
     */
    private ReadResult read(Jwt jwt, Filter filter) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        String order = filter.limit() == null ? "bet.created_at, bet.id" : "bet.created_at DESC, bet.id DESC";
        return jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                )
                SELECT EXISTS (
                    SELECT 1 FROM table_members m JOIN tables t ON t.id = m.table_id
                    WHERE m.user_id = viewer.id AND t.id = ?::uuid AND t.archived_at IS NULL
                ) AS table_accessible,
                CASE WHEN b.id IS NULL THEN NULL ELSE
                    jsonb_build_object(
                        'id', b.id, 'tableId', b.table_id, 'proposerId', b.proposer_id,
                        'modeId', b.mode, 'config', b.config, 'question', b.question,
                        'choices', b.choices, 'wagerCents', b.wager_cents, 'forFun', b.is_4fun,
                        'status', b.status, 'locksAt', b.locks_at, 'createdAt', b.created_at,
                        'pointsSettled', b.points_settled_at IS NOT NULL,
                        'pickCount', (SELECT count(*) FROM picks p WHERE p.bet_id = b.id),
                        'myPick', own.choice,
                        'myParticipation', CASE WHEN own.choice IS NOT NULL THEN 'picked'
                            WHEN EXISTS (SELECT 1 FROM bet_participants bp
                                         WHERE bp.bet_id = b.id AND bp.user_id = viewer.id) THEN 'no_guess'
                            ELSE 'not_joined' END
                    ) || jsonb_strip_nulls(jsonb_build_object(
                        'gameId', b.game_id, 'baseline', b.baseline,
                        'includesOvertime', b.includes_overtime, 'settledBy', b.settler_rule,
                        'sourceBetId', b.source_bet_id, 'sourceTableId', b.source_table_id,
                        'overriddenBy', b.overridden_by, 'overriddenAt', b.overridden_at,
                        'overridden', CASE WHEN b.source_bet_id IS NOT NULL THEN b.overridden END
                    )) ||
                    CASE WHEN b.status = 'ACTIVE' THEN '{}'::jsonb ELSE
                        jsonb_build_object(
                            'remainderRule', b.remainder_rule,
                            'picks', (SELECT COALESCE(jsonb_agg(
                                jsonb_build_object('userId', bp.user_id, 'choice', p.choice)
                                ORDER BY bp.participation_order), '[]'::jsonb)
                                FROM bet_participants bp JOIN picks p
                                  ON p.bet_id = bp.bet_id AND p.user_id = bp.user_id WHERE bp.bet_id = b.id),
                            'noGuessUserIds', (SELECT COALESCE(jsonb_agg(bp.user_id ORDER BY bp.participation_order),
                                                               '[]'::jsonb)
                                FROM bet_participants bp LEFT JOIN picks p
                                  ON p.bet_id = bp.bet_id AND p.user_id = bp.user_id
                                WHERE bp.bet_id = b.id AND p.user_id IS NULL)
                        ) || jsonb_strip_nulls(jsonb_build_object(
                            'lockedAt', b.locked_at, 'remainderHostId', b.remainder_host_id))
                    END ||
                    CASE WHEN b.status IN ('SETTLED', 'WASHED') THEN
                        jsonb_strip_nulls(jsonb_build_object(
                            'closedAt', b.closed_at, 'settledAt', b.points_settled_at,
                            'calledBy', b.called_by, 'winningChoice', b.winning_choice,
                            'washReason', b.wash_reason
                        )) || jsonb_build_object('deltasCents', (
                            SELECT COALESCE(jsonb_object_agg(d.user_id::text, d.amount), '{}'::jsonb)
                            FROM (SELECT user_id, sum(delta_cents) AS amount FROM ledger_entries
                                  WHERE bet_id = b.id GROUP BY user_id HAVING sum(delta_cents) <> 0) d
                        ))
                    ELSE '{}'::jsonb END
                END AS bet
                FROM viewer
                LEFT JOIN LATERAL (
                    SELECT bet.* FROM bets bet
                    JOIN tables t ON t.id = bet.table_id AND t.archived_at IS NULL
                    JOIN table_members m ON m.table_id = t.id AND m.user_id = viewer.id
                    WHERE (?::uuid IS NULL OR bet.table_id = ?::uuid)
                      AND (?::uuid IS NULL OR bet.id = ?::uuid)
                      AND (?::text IS NULL OR bet.status = ?::text)
                      AND (NOT ?::boolean OR bet.status IN ('ACTIVE', 'PENDING')
                           OR (bet.status = 'SETTLED' AND NOT bet.is_4fun AND bet.points_settled_at IS NULL))
                      AND (?::uuid IS NULL OR bet.proposer_id = ?::uuid OR EXISTS (
                           SELECT 1 FROM bet_participants involved
                           WHERE involved.bet_id = bet.id AND involved.user_id = ?::uuid
                             AND (bet.status <> 'ACTIVE' OR involved.user_id = viewer.id)))
                      AND (?::timestamptz IS NULL OR (bet.created_at, bet.id) < (?::timestamptz, ?::uuid))
                    ORDER BY %s
                    LIMIT ?::int
                ) b ON true
                LEFT JOIN picks own ON own.bet_id = b.id AND own.user_id = viewer.id
                """.formatted(order), rs -> {
                    if (!rs.next()) return new ReadResult(false, false, List.of());
                    boolean accessible = rs.getBoolean("table_accessible");
                    List<JsonNode> bets = new ArrayList<>();
                    do {
                        String value = rs.getString("bet");
                        if (value != null) bets.add(parseBet(value));
                    } while (rs.next());
                    return new ReadResult(true, accessible, withCapabilities(userId, bets));
                }, sessionId, userId, filter.tableId(), filter.tableId(), filter.tableId(), filter.betId(),
                filter.betId(), filter.status(), filter.status(), filter.live(), filter.involving(),
                filter.involving(), filter.involving(), filter.afterCreatedAt(), filter.afterCreatedAt(),
                filter.afterId(), filter.limit());
    }

    private JsonNode parseBet(String value) {
        try {
            JsonNode bet = json.readTree(value);
            JsonNode deltas = bet.path("deltasCents");
            if (deltas.isObject()) {
                deltas.elements().forEachRemaining(delta -> {
                    if (!delta.canConvertToLong()) throw new IllegalStateException("Bet delta exceeds long range");
                });
            }
            return bet;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read bet projection", exception);
        }
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return error(401, "unauthorized", "Authentication required or credentials invalid.");
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return error(400, "bad_request", message);
    }

    private static ResponseEntity<Map<String, String>> notFound() {
        return error(404, "not_found", "Bet or table not found.");
    }

    private record ReadResult(boolean authenticated, boolean tableAccessible, List<JsonNode> bets) {}

    private record Filter(UUID tableId, UUID betId, String status, boolean live, UUID involving,
                          OffsetDateTime afterCreatedAt, UUID afterId, Integer limit) {}

    private record Member(UUID userId, boolean host, boolean eligible) {}

    /**
     * Adds what the viewer may do with each bet, using the same rules the mutations enforce: `viewerCanCall` (call
     * a Pending U2Pick's result, per its settler rule) and `viewerCanOverride` (a host on a sports bet before points
     * are settled). The client only renders these.
     */
    private List<JsonNode> withCapabilities(UUID viewer, List<JsonNode> bets) {
        if (bets.isEmpty()) return List.of();
        Set<String> tableIds = new HashSet<>();
        bets.forEach(bet -> tableIds.add(bet.path("tableId").asText()));
        Map<String, List<Member>> members = new HashMap<>();
        jdbc.query("""
                SELECT m.table_id, m.user_id, m.role, u.status, u.role AS account_role
                FROM table_members m JOIN users u ON u.id = m.user_id
                WHERE m.table_id = ANY (?::uuid[])
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> members
                .computeIfAbsent(rs.getString("table_id"), ignored -> new ArrayList<>())
                .add(new Member(rs.getObject("user_id", UUID.class), rs.getString("role").equals("HOST"),
                        rs.getString("status").equals("ACTIVE") && rs.getString("account_role").equals("PLAYER"))),
                (Object) tableIds.toArray(String[]::new));
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode bet : bets) {
            ObjectNode withFlags = ((ObjectNode) bet).deepCopy();
            List<Member> atTable = members.getOrDefault(bet.path("tableId").asText(), List.of());
            boolean host = atTable.stream().anyMatch(member -> member.userId().equals(viewer) && member.host());
            withFlags.put("viewerCanCall", canCall(bet, viewer, atTable));
            withFlags.put("viewerCanOverride", host
                    && SportsProposalService.MODES.contains(bet.path("modeId").asText())
                    && !bet.path("pointsSettled").asBoolean());
            result.add(withFlags);
        }
        return List.copyOf(result);
    }

    private static boolean canCall(JsonNode bet, UUID viewer, List<Member> atTable) {
        if (!bet.path("modeId").asText().equals("u2pick") || bet.has("sourceBetId")
                || !bet.path("status").asText().equals("PENDING") || !bet.path("settledBy").isTextual()) {
            return false;
        }
        Set<UUID> eligible = new HashSet<>();
        Set<UUID> hosts = new HashSet<>();
        for (Member member : atTable) {
            if (!member.eligible()) continue;
            eligible.add(member.userId());
            if (member.host()) hosts.add(member.userId());
        }
        Set<UUID> inPot = new HashSet<>();
        bet.path("picks").forEach(pick -> inPot.add(UUID.fromString(pick.path("userId").asText())));
        U2PickSettler.Rule rule = U2PickSettler.Rule.valueOf(bet.path("settledBy").asText().toUpperCase(Locale.ROOT));
        return U2PickSettler.canSettle(rule, viewer, UUID.fromString(bet.path("proposerId").asText()), eligible,
                hosts, inPot);
    }
}
