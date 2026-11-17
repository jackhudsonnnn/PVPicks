package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.sports.GameLookup;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Total Disaster's lock wash (domain rules, "Wash triggers"): a combined score that already passed the line before
 * picks locked decides the bet before anyone could react, as Prop Hunt's line rule does.
 */
@Component
class ScoreLockRules implements LockWashRule {
    private final JdbcTemplate jdbc;
    private final GameLookup games;
    private final ObjectMapper json;

    ScoreLockRules(JdbcTemplate jdbc, GameLookup games, ObjectMapper json) {
        this.jdbc = jdbc;
        this.games = games;
        this.json = json;
    }

    @Override
    public Optional<String> washAtLock(UUID betId, String mode) {
        if (!mode.equals("total_disaster")) return Optional.empty();
        var row = jdbc.queryForMap("SELECT game_id, config::text AS config, includes_overtime FROM bets WHERE id = ?",
                betId);
        Optional<GameLookup.Game> game = games.find((String) row.get("game_id"));
        if (game.isEmpty()) return Optional.empty();
        BigDecimal line;
        try {
            line = json.readTree((String) row.get("config")).path("line").decimalValue();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read bet", exception);
        }
        int combined = SportsOutcome.combinedScore(game.get(), !Boolean.FALSE.equals(row.get("includes_overtime")));
        return BigDecimal.valueOf(combined).compareTo(line) >= 0
                ? Optional.of(PlayerLockRules.LINE_REACHED) : Optional.empty();
    }
}
