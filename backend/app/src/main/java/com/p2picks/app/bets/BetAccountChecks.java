package com.p2picks.app.bets;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Whether an account still has something riding on bets, which blocks deleting it until it's square. */
@Component
public class BetAccountChecks {
    private final JdbcTemplate jdbc;

    BetAccountChecks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Proposed or joined an Active or Pending bet at a current table. */
    public boolean inOpenBets(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bets b JOIN tables t ON t.id = b.table_id AND t.archived_at IS NULL
                    WHERE b.status IN ('ACTIVE', 'PENDING')
                      AND (b.proposer_id = ? OR EXISTS (SELECT 1 FROM bet_participants p
                                                       WHERE p.bet_id = b.id AND p.user_id = ?)))
                """, Boolean.class, userId, userId));
    }

    /** Won or lost points on a resolved bet whose table points haven't been settled yet. */
    public boolean hasUnsettledPoints(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ledger_entries l JOIN bets b ON b.id = l.bet_id
                    WHERE l.user_id = ? AND b.status = 'SETTLED' AND NOT b.is_4fun AND b.points_settled_at IS NULL
                    GROUP BY l.bet_id HAVING sum(l.delta_cents) <> 0)
                """, Boolean.class, userId));
    }
}
