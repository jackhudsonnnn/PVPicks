package com.p2picks.app.bets;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves Blackjack hands along on time: deals when a join window closes, and stands (or declines insurance for)
 * anyone whose decision timer ran out. Runs every second with the other bet workers.
 */
@Component
class BlackjackWorker {
    private static final Logger log = LoggerFactory.getLogger(BlackjackWorker.class);
    private static final int PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final BlackjackService blackjack;

    BlackjackWorker(JdbcTemplate jdbc, BlackjackService blackjack) {
        this.jdbc = jdbc;
        this.blackjack = blackjack;
    }

    void poll() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM bets WHERE mode = 'blackjack' AND status = 'ACTIVE' AND locks_at <= clock_timestamp()
                ORDER BY locks_at LIMIT ?
                """, UUID.class, PAGE_SIZE);
        for (UUID betId : due) {
            try {
                blackjack.deal(betId);
            } catch (BlackjackService.SeatsChanged exception) {
                log.debug("Blackjack {} seats changed while dealing; will retry", betId);
            } catch (RuntimeException exception) {
                log.error("Failed to deal Blackjack {}; will retry", betId, exception);
            }
        }
        List<UUID> lapsed = jdbc.queryForList("""
                SELECT r.bet_id FROM blackjack_rounds r JOIN bets b ON b.id = r.bet_id AND b.status = 'PENDING'
                WHERE r.next_deadline <= clock_timestamp()
                ORDER BY r.next_deadline LIMIT ?
                """, UUID.class, PAGE_SIZE);
        for (UUID betId : lapsed) {
            try {
                blackjack.expire(betId);
            } catch (RuntimeException exception) {
                log.error("Failed to time out Blackjack {}; will retry", betId, exception);
            }
        }
    }
}
