package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
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

    @GetMapping("/tables/{tableId}/bets")
    ResponseEntity<?> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        ReadResult result = read(jwt, tableId, null);
        if (!result.authenticated()) return unauthorized();
        if (!result.tableAccessible()) return notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.bets());
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
     * The caller's bets across their current tables (or one of them), newest first, with per-status counts for
     * the same scope. Uses the same projection as the table list, so active picks stay private.
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
        ReadResult result = read(jwt, new Filter(tableId, null, status,
                after == null ? null : after.createdAt(), after == null ? null : after.id(), size + 1));
        if (!result.authenticated()) return unauthorized();
        if (tableId != null && !result.tableAccessible()) return notFound();
        List<JsonNode> page = result.bets().size() > size ? result.bets().subList(0, size) : result.bets();
        String next = result.bets().size() > size ? Cursor.of(page.getLast()).encode() : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page);
        body.put("nextCursor", next);
        body.put("counts", counts(UUID.fromString(jwt.getSubject()), tableId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private Map<String, Long> counts(UUID userId, UUID tableId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("ACTIVE", "PENDING", "SETTLED", "WASHED")) counts.put(status, 0L);
        jdbc.query("""
                SELECT bet.status, count(*) AS total FROM bets bet
                JOIN tables t ON t.id = bet.table_id AND t.archived_at IS NULL
                JOIN table_members m ON m.table_id = t.id AND m.user_id = ?
                WHERE ?::uuid IS NULL OR bet.table_id = ?::uuid
                GROUP BY bet.status
                """, rs -> {
            counts.put(rs.getString("status"), rs.getLong("total"));
        }, userId, tableId, tableId);
        return counts;
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
        return read(jwt, new Filter(tableId, betId, null, null, null, null));
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
                    return new ReadResult(true, accessible, List.copyOf(bets));
                }, sessionId, userId, filter.tableId(), filter.tableId(), filter.tableId(), filter.betId(),
                filter.betId(), filter.status(), filter.status(), filter.afterCreatedAt(), filter.afterCreatedAt(),
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

    private record Filter(UUID tableId, UUID betId, String status, OffsetDateTime afterCreatedAt, UUID afterId,
                          Integer limit) {}
}
