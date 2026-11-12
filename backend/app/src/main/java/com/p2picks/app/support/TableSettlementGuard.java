package com.p2picks.app;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class TableSettlementGuard {
    private final JdbcTemplate jdbc;

    TableSettlementGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasUnsettledBets(UUID tableId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bets WHERE table_id = ? AND
                    (status IN ('ACTIVE', 'PENDING')
                     OR (status = 'SETTLED' AND NOT is_4fun AND points_settled_at IS NULL)))
                """, Boolean.class, tableId), "Unsettled bet check returned no result");
    }
}
