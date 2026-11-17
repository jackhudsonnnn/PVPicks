package com.p2picks.app.bets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Washes Pending U2Pick bets that nobody called within seven days of locking (domain rules, "Wash triggers"), so a
 * forgotten bet can't stay open forever. Runs on the lock worker's schedule; each bet washes in its own transaction,
 * locking users, then table, then bet, and failures are retried on later runs. Quick Pick originals with copies and
 * the copies themselves are skipped until result propagation exists.
 */
@Component
class U2PickExpiry {
    static final Duration LIMIT = Duration.ofDays(7);
    static final String REASON = "Nobody called the result";
    private static final Logger log = LoggerFactory.getLogger(U2PickExpiry.class);

    private final JdbcTemplate jdbc;
    private final SportsBetLoader loader;
    private final BetResolutionWriter writer;
    private final TransactionTemplate transaction;
    private final Clock clock;

    U2PickExpiry(JdbcTemplate jdbc, SportsBetLoader loader, BetResolutionWriter writer,
            PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.writer = writer;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    void poll() {
        Instant now = clock.instant();
        List<UUID> overdue = jdbc.query("""
                SELECT b.id FROM bets b
                WHERE b.mode = 'u2pick' AND b.status = 'PENDING' AND b.source_bet_id IS NULL AND b.locked_at <= ?
                  AND NOT EXISTS (SELECT 1 FROM bets copy WHERE copy.source_bet_id = b.id)
                ORDER BY b.locked_at, b.id LIMIT 500
                """, (rs, row) -> rs.getObject("id", UUID.class), java.sql.Timestamp.from(now.minus(LIMIT)));
        for (UUID bet : overdue) {
            try {
                expire(bet, now);
            } catch (RuntimeException exception) {
                log.error("Failed to wash uncalled U2Pick bet {}; will retry", bet, exception);
            }
        }
    }

    /** Washes the bet if it is still a Pending U2Pick locked at least {@link #LIMIT} before {@code now}. */
    boolean expire(UUID betId, Instant now) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            List<UUID> affected = loader.affectedUsers(betId);
            if (affected == null) return false;
            String ids = "{" + String.join(",", affected.stream().map(UUID::toString).toList()) + "}";
            Set<UUID> locked = new HashSet<>(jdbc.query("SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id "
                    + "FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class), ids));
            SportsBetLoader.Loaded bet = loader.load(betId, locked);
            if (bet == null || !bet.mode().equals("u2pick") || !bet.status().equals("PENDING")) return false;
            Instant lockedAt = bet.terms().lockedAt();
            if (lockedAt == null || now.isBefore(lockedAt.plus(LIMIT))) return false;
            Boolean copied = jdbc.queryForObject("""
                    SELECT source_bet_id IS NOT NULL OR EXISTS (SELECT 1 FROM bets c WHERE c.source_bet_id = b.id)
                    FROM bets b WHERE b.id = ?
                    """, Boolean.class, betId);
            if (Boolean.TRUE.equals(copied)) return false;
            writer.apply(bet.bet(), null, REASON, null, false);
            return true;
        }));
    }
}
