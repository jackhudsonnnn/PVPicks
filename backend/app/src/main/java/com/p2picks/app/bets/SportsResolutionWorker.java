package com.p2picks.app.bets;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks Pending sports bets against the feed and settles the ones whose result is decided. Runs on the lock
 * worker's schedule; each bet resolves in its own transaction, and failures are retried on later runs.
 */
@Component
class SportsResolutionWorker {
    private static final Logger log = LoggerFactory.getLogger(SportsResolutionWorker.class);
    private final JdbcTemplate jdbc;
    private final SportsResolutionService resolutions;
    private final Clock clock;

    SportsResolutionWorker(JdbcTemplate jdbc, SportsResolutionService resolutions, Clock clock) {
        this.jdbc = jdbc;
        this.resolutions = resolutions;
        this.clock = clock;
    }

    void poll() {
        List<UUID> pending = jdbc.query("""
                SELECT id FROM bets
                WHERE status = 'PENDING' AND mode = ANY (?) AND source_bet_id IS NULL
                ORDER BY locks_at, id LIMIT 500
                """, (rs, row) -> rs.getObject("id", UUID.class),
                (Object) SportsProposalService.MODES.toArray(String[]::new));
        for (UUID bet : pending) {
            try {
                resolutions.resolve(bet, clock.instant());
            } catch (RuntimeException exception) {
                log.error("Failed to resolve sports bet {}; will retry", bet, exception);
            }
        }
    }
}
