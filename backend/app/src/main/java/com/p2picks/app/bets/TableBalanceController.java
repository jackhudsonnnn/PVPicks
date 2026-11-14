package com.p2picks.app.bets;

import static com.p2picks.app.common.ApiErrors.error;

import java.math.BigDecimal;
import java.util.ArrayList;
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

/**
 * Each current member's points at a table, for the seats and the Peers tab.
 *
 * <p>{@code settleCents} is what the next points settlement would move: the member's ledger deltas from resolved
 * bets whose points are not settled yet. {@code trueCents} also subtracts their wagers in locked (Pending) pots, the
 * worst case if they lose every one. Members can already read every input here (resolved deltas and post-lock picks
 * are part of each bet's projection), so this exposes nothing new; Active picks are never counted.
 */
@RestController
class TableBalanceController {
    private final JdbcTemplate jdbc;

    TableBalanceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/tables/{tableId}/balances")
    ResponseEntity<?> balances(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID sessionId = UUID.fromString(jwt.getId());
        Result result = jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                ), access AS (
                    SELECT EXISTS (
                        SELECT 1 FROM table_members m JOIN tables t ON t.id = m.table_id
                        WHERE m.user_id = viewer.id AND t.id = ? AND t.archived_at IS NULL
                    ) AS ok FROM viewer
                )
                SELECT access.ok, member.user_id, member.settle, member.staked
                FROM access
                LEFT JOIN LATERAL (
                    SELECT m.user_id,
                           COALESCE((SELECT sum(le.delta_cents) FROM ledger_entries le
                                     JOIN bets b ON b.id = le.bet_id
                                     WHERE le.table_id = m.table_id AND le.user_id = m.user_id
                                       AND b.status = 'SETTLED' AND b.points_settled_at IS NULL), 0) AS settle,
                           COALESCE((SELECT sum(b.wager_cents) FROM bets b
                                     JOIN picks p ON p.bet_id = b.id
                                     WHERE b.table_id = m.table_id AND p.user_id = m.user_id
                                       AND b.status = 'PENDING' AND NOT b.is_4fun), 0) AS staked
                    FROM table_members m
                    WHERE m.table_id = ? AND access.ok
                    ORDER BY m.user_id
                ) member ON true
                """, rs -> {
            if (!rs.next()) return new Result(false, false, List.of());
            boolean ok = rs.getBoolean("ok");
            List<Row> rows = new ArrayList<>();
            do {
                String id = rs.getString("user_id");
                if (id != null) rows.add(new Row(UUID.fromString(id), rs.getBigDecimal("settle"),
                        rs.getBigDecimal("staked")));
            } while (rs.next());
            return new Result(true, ok, rows);
        }, sessionId, userId, tableId, tableId);
        if (result == null || !result.authenticated()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        if (!result.accessible()) return error(404, "not_found", "Table not found.");
        List<Map<String, Object>> body = new ArrayList<>();
        try {
            for (Row row : result.rows()) {
                Map<String, Object> balance = new LinkedHashMap<>();
                balance.put("userId", row.userId());
                balance.put("settleCents", row.settle().longValueExact());
                balance.put("trueCents", row.settle().subtract(row.staked()).longValueExact());
                body.add(balance);
            }
        } catch (ArithmeticException outOfRange) {
            return error(409, "balance_out_of_range", "A table balance is outside the supported range.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private record Row(UUID userId, BigDecimal settle, BigDecimal staked) {}

    private record Result(boolean authenticated, boolean accessible, List<Row> rows) {}
}
