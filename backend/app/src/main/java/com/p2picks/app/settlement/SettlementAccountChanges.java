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

    /** Owes or is owed on an open receipt, or is party to a sent settle that can still be rejected. */
    public boolean hasOpenReceipts(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_lines
                               WHERE (payer_id = ? OR payee_id = ?) AND status = 'unpaid')
                    OR EXISTS (SELECT 1 FROM settles
                               WHERE (sender_id = ? OR other_id = ?) AND kind = 'sent' AND rejected_at IS NULL
                                 AND reject_by > clock_timestamp())
                """, Boolean.class, userId, userId, userId, userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        jdbc.update("DELETE FROM debt_caps WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM settlement_pair_reviews WHERE user_id = ? OR other_user_id = ?", userId, userId);
    }
}
