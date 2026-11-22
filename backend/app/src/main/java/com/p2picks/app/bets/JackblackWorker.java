package com.p2picks.app.bets;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves Jackblack hands along on time: deals when a join window closes, stands anyone whose
 * decision timer ran out, and plays out an idle dealer's turn. Runs every second with the other bet workers.
 */
@Component
class JackblackWorker {
    private static final Logger log = LoggerFactory.getLogger(JackblackWorker.class);
    private static final int PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final JackblackService jackblack;

    JackblackWorker(JdbcTemplate jdbc, JackblackService jackblack) {
        this.jdbc = jdbc;
        this.jackblack = jackblack;
    }

    void poll() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM bets WHERE mode = 'jackblack' AND status = 'ACTIVE' AND locks_at <= clock_timestamp()
                ORDER BY locks_at LIMIT ?
                """, UUID.class, PAGE_SIZE);
        for (UUID betId : due) {
            try {
                jackblack.deal(betId);
            } catch (JackblackService.PlayersChanged exception) {
                log.debug("Jackblack {} players changed while dealing; will retry", betId);
            } catch (RuntimeException exception) {
                log.error("Failed to deal Jackblack {}; will retry", betId, exception);
            }
        }
        List<UUID> lapsed = jdbc.queryForList("""
                SELECT r.bet_id FROM jackblack_games r JOIN bets b ON b.id = r.bet_id AND b.status = 'PENDING'
                WHERE r.next_deadline <= clock_timestamp()
                ORDER BY r.next_deadline LIMIT ?
                """, UUID.class, PAGE_SIZE);
        for (UUID betId : lapsed) {
            try {
                jackblack.expire(betId);
            } catch (RuntimeException exception) {
                log.error("Failed to time out Jackblack {}; will retry", betId, exception);
            }
        }
    }
}
