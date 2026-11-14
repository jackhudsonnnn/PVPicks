package com.p2picks.app.accounts;

import com.p2picks.app.tables.TableAccountChanges;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezing and unfreezing. A frozen account can't sign in: its sessions end at once and sign-in says it's frozen.
 * Its bets, picks and receipts are untouched, and tables it hosted get a new host (see `TableAccountChanges`).
 * Unfreezing restores sign-in but not hosting. Callers hold the account's user lock.
 */
@Component
public class AccountStatus {
    private final JdbcTemplate jdbc;
    private final TableAccountChanges tables;

    AccountStatus(JdbcTemplate jdbc, TableAccountChanges tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    /** Freezes an active player; returns whether anything changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean freeze(UUID userId) {
        int changed = jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ? AND status = 'ACTIVE'", userId);
        if (changed == 0) return false;
        revokeSessions(userId);
        tables.deactivated(userId);
        return true;
    }

    /** Restores sign-in for a frozen account; returns whether anything changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean unfreeze(UUID userId) {
        return jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ? AND status = 'FROZEN'", userId) == 1;
    }

    void revokeSessions(UUID userId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = clock_timestamp() WHERE user_id = ? AND revoked_at IS NULL",
                userId);
    }
}
