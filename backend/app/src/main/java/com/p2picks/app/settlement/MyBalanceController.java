package com.p2picks.app.settlement;

import static com.p2picks.app.common.ApiErrors.error;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Your overall balance, for the points pill (domain rules, "Balances"): every current table's balance (unsettled
 * results minus points in play) plus the net of your open settle-up receipts. A host settle moves points from a
 * table into receipts, so it leaves this number unchanged. One statement, so one snapshot.
 */
@RestController
class MyBalanceController {
    private final JdbcTemplate jdbc;

    MyBalanceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me/balance")
    ResponseEntity<?> balance(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<BigDecimal> totals = jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
                    WHERE s.id = ? AND u.id = ? AND s.revoked_at IS NULL
                      AND s.expires_at > now() AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                )
                SELECT
                    COALESCE((SELECT sum(e.delta_cents)
                              FROM ledger_entries e
                              JOIN bets b ON b.id = e.bet_id
                              JOIN tables t ON t.id = e.table_id AND t.archived_at IS NULL
                              JOIN table_members m ON m.table_id = t.id AND m.user_id = viewer.id
                              WHERE e.user_id = viewer.id AND b.status = 'SETTLED'
                                AND b.points_settled_at IS NULL), 0)
                  - COALESCE((SELECT sum(b.wager_cents)
                              FROM bets b
                              JOIN picks p ON p.bet_id = b.id AND p.user_id = viewer.id
                              JOIN tables t ON t.id = b.table_id AND t.archived_at IS NULL
                              JOIN table_members m ON m.table_id = t.id AND m.user_id = viewer.id
                              WHERE b.status = 'PENDING' AND NOT b.is_4fun), 0)
                  -- Card games in play (Jackblack, Hold'em): each game's worst case (domain rules).
                  - COALESCE((SELECT sum(j.risk_cents)
                              FROM bets b
                              JOIN card_players j ON j.bet_id = b.id AND j.user_id = viewer.id
                              JOIN tables t ON t.id = b.table_id AND t.archived_at IS NULL
                              JOIN table_members m ON m.table_id = t.id AND m.user_id = viewer.id
                              WHERE b.status = 'PENDING' AND NOT b.is_4fun), 0)
                  + COALESCE((SELECT sum(CASE WHEN l.payer_id = viewer.id THEN -l.amount_cents
                                              ELSE l.amount_cents END)
                              FROM settlement_lines l
                              WHERE (l.payer_id = viewer.id OR l.payee_id = viewer.id)
                                AND l.status = 'unpaid'), 0) AS total
                FROM viewer
                """, (rs, row) -> rs.getBigDecimal("total"), UUID.fromString(jwt.getId()), userId);
        if (totals.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        long cents;
        try {
            cents = totals.getFirst().longValueExact();
        } catch (ArithmeticException outOfRange) {
            return error(409, "balance_out_of_range", "Your balance is outside the supported range.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Balance(cents));
    }

    private record Balance(long balanceCents) {}
}
