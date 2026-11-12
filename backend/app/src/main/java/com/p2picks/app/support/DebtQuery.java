package com.p2picks.app;

import com.p2picks.modules.settlement.api.DebtCalculator;
import com.p2picks.shared.kernel.Money;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
class DebtQuery {
    private final JdbcTemplate jdbc;

    DebtQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BigInteger> read(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<DebtInput> inputs = jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM auth_sessions session
                    JOIN users u ON u.id = session.user_id
                    WHERE session.id = ? AND u.id = ? AND session.revoked_at IS NULL
                      AND session.expires_at > now() AND u.status = 'ACTIVE'
                )
                SELECT input.kind, input.id, input.other_id, input.results, input.pending, input.amount
                FROM viewer
                LEFT JOIN LATERAL (
                    SELECT 'table' AS kind, m.table_id AS id, NULL::uuid AS other_id,
                        COALESCE((
                            SELECT SUM(e.delta_cents)
                            FROM ledger_entries e JOIN bets b ON b.id = e.bet_id
                            WHERE e.user_id = viewer.id AND e.table_id = m.table_id
                              AND b.status = 'SETTLED' AND b.points_settled_at IS NULL AND NOT b.is_4fun
                        ), 0)::numeric AS results,
                        COALESCE((
                            SELECT SUM(b.wager_cents)
                            FROM bets b JOIN picks p ON p.bet_id = b.id AND p.user_id = viewer.id
                            WHERE b.table_id = m.table_id AND b.status = 'PENDING' AND NOT b.is_4fun
                        ), 0)::numeric AS pending,
                        NULL::bigint AS amount
                    FROM table_members m JOIN tables t ON t.id = m.table_id AND t.archived_at IS NULL
                    WHERE m.user_id = viewer.id
                    UNION ALL
                    SELECT 'receipt', l.id,
                        CASE WHEN l.payer_id = viewer.id THEN l.payee_id ELSE l.payer_id END,
                        NULL::numeric, NULL::numeric,
                        CASE WHEN l.payer_id = viewer.id THEN -l.amount_cents ELSE l.amount_cents END
                    FROM settlement_lines l
                    WHERE (l.payer_id = viewer.id OR l.payee_id = viewer.id)
                      AND l.status NOT IN ('paid', 'forgiven')
                ) input ON true
                """, (rs, row) -> new DebtInput(rs.getString("kind"), rs.getObject("id", UUID.class),
                        rs.getObject("other_id", UUID.class),
                        rs.getBigDecimal("results") == null ? null : rs.getBigDecimal("results").toBigIntegerExact(),
                        rs.getBigDecimal("pending") == null ? null : rs.getBigDecimal("pending").toBigIntegerExact(),
                        rs.getObject("amount", Long.class)), UUID.fromString(jwt.getId()), userId);
        if (inputs.isEmpty()) {
            return Optional.empty();
        }
        List<DebtCalculator.TableBalance> tables = new ArrayList<>();
        List<DebtCalculator.OpenReceipt> receipts = new ArrayList<>();
        for (DebtInput input : inputs) {
            if (input.kind() == null) continue;
            switch (input.kind()) {
                case "table" -> tables.add(new DebtCalculator.TableBalance(input.id(), input.results(), input.pending()));
                case "receipt" -> receipts.add(new DebtCalculator.OpenReceipt(
                        input.id(), input.otherId(), new Money(input.amount())));
                default -> throw new IllegalStateException("Unknown debt input kind: " + input.kind());
            }
        }
        return Optional.of(DebtCalculator.calculateExact(userId, tables, receipts));
    }

    private record DebtInput(
            String kind, UUID id, UUID otherId, BigInteger results, BigInteger pending, Long amount) {}
}
