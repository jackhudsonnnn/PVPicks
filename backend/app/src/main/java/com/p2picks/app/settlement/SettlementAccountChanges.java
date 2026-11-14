package com.p2picks.app.settlement;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement's part of deleting an account: open receipts block it; afterwards the debt cap and the account's own
 * balance-change warnings go. Receipts stay, showing "Deleted user" to the other side.
 */
@Component
public class SettlementAccountChanges {
    private final JdbcTemplate jdbc;

    SettlementAccountChanges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owes or is owed on a receipt that isn't paid or forgiven. */
    public boolean hasOpenReceipts(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_lines
                               WHERE (payer_id = ? OR payee_id = ?) AND status NOT IN ('paid', 'forgiven'))
                """, Boolean.class, userId, userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        jdbc.update("DELETE FROM debt_caps WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM settlement_pair_reviews WHERE user_id = ? OR other_user_id = ?", userId, userId);
    }
}
