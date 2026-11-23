package com.p2picks.app.bets;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves the card games (Jackblack, Hold'em) along every second: deals a game once its join window closes, and
 * applies lapsed timers (a turn, a decision, the pause between rounds or hands). Each bet goes to its mode's service.
 */
@Component
class CardGameWorker {
    private static final Logger log = LoggerFactory.getLogger(CardGameWorker.class);
    private static final int PAGE_SIZE = 50;
    private final JdbcTemplate jdbc;
    private final JackblackService jackblack;
    private final HoldemService holdem;

    CardGameWorker(JdbcTemplate jdbc, JackblackService jackblack, HoldemService holdem) {
        this.jdbc = jdbc;
        this.jackblack = jackblack;
        this.holdem = holdem;
    }

    private record Due(UUID betId, String mode) {}

    void poll() {
        List<Due> due = jdbc.query("""
                SELECT id, mode FROM bets WHERE mode IN ('jackblack', 'holdem') AND status = 'ACTIVE'
                  AND locks_at <= clock_timestamp()
                ORDER BY locks_at LIMIT ?
                """, (rs, row) -> new Due(rs.getObject("id", UUID.class), rs.getString("mode")), PAGE_SIZE);
        for (Due bet : due) {
            try {
                if (bet.mode().equals(HoldemService.MODE)) holdem.deal(bet.betId());
                else jackblack.deal(bet.betId());
            } catch (CardGames.PlayersChanged exception) {
                log.debug("{} {} players changed while dealing; will retry", bet.mode(), bet.betId());
            } catch (RuntimeException exception) {
                log.error("Failed to deal {} {}; will retry", bet.mode(), bet.betId(), exception);
            }
        }
        List<Due> lapsed = jdbc.query("""
                SELECT g.bet_id, b.mode FROM card_games g JOIN bets b ON b.id = g.bet_id AND b.status = 'PENDING'
                WHERE g.next_deadline <= clock_timestamp()
                ORDER BY g.next_deadline LIMIT ?
                """, (rs, row) -> new Due(rs.getObject("bet_id", UUID.class), rs.getString("mode")), PAGE_SIZE);
        for (Due bet : lapsed) {
            try {
                if (bet.mode().equals(HoldemService.MODE)) holdem.expire(bet.betId());
                else jackblack.expire(bet.betId());
            } catch (RuntimeException exception) {
                log.error("Failed to time out {} {}; will retry", bet.mode(), bet.betId(), exception);
            }
        }
    }
}
