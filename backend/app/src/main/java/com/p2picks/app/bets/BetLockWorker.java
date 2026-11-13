package com.p2picks.app.bets;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class BetLockWorker {
    private static final Logger log = LoggerFactory.getLogger(BetLockWorker.class);
    private static final int PAGE_SIZE = 100;
    private static final Set<String> SUPPORTED_MODES = Set.of("u2pick");
    private final JdbcTemplate jdbc;
    private final BetLockService locks;
    private final TransactionTemplate transaction;

    BetLockWorker(JdbcTemplate jdbc, BetLockService locks, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.locks = locks;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void poll() {
        OffsetDateTime horizon = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (horizon == null) throw new IllegalStateException("Database clock is unavailable");
        Due cursor = null;
        List<Due> page;
        do {
            page = jdbc.query("""
                    SELECT id, locks_at, mode FROM bets
                    WHERE status = 'ACTIVE' AND locks_at <= ?
                      AND (?::timestamptz IS NULL OR (locks_at, id) > (?::timestamptz, ?::uuid))
                    ORDER BY locks_at, id LIMIT ?
                    """, (rs, row) -> new Due(rs.getObject("id", UUID.class),
                            rs.getObject("locks_at", OffsetDateTime.class), rs.getString("mode")),
                    horizon, cursor == null ? null : cursor.deadline(),
                    cursor == null ? null : cursor.deadline(), cursor == null ? null : cursor.id(), PAGE_SIZE);
            for (Due due : page) {
                if (!SUPPORTED_MODES.contains(due.mode())) {
                    log.error("Cannot automatically lock overdue bet {}: sports-feed lifecycle is not implemented", due.id());
                    continue;
                }
                try {
                    transaction.executeWithoutResult(ignored -> {
                        jdbc.execute("SET LOCAL lock_timeout = '2s'");
                        locks.lock(due.id(), SUPPORTED_MODES);
                    });
                } catch (BetLockService.ParticipantsChanged exception) {
                    log.warn("Bet {} participants changed during locking; will retry", due.id());
                } catch (RuntimeException exception) {
                    log.error("Failed to lock overdue bet {}; will retry", due.id(), exception);
                }
            }
            if (!page.isEmpty()) cursor = page.getLast();
        } while (page.size() == PAGE_SIZE);
    }

    private record Due(UUID id, OffsetDateTime deadline, String mode) {}
}
