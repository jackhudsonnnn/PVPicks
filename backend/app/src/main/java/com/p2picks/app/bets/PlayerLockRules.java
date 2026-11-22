package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.sports.PlayerStatsLookup;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The player modes' lock washes (domain rules, "Wash triggers"): a Prop Hunt line the player already reached, or a
 * King Of The Hill target either player already reached, before picks locked. The outcome would be decided before
 * anyone could react, so the bet washes.
 */
@Component
class PlayerLockRules implements LockWashRule {
    static final String LINE_REACHED = "Line reached before picks locked";
    static final String TARGET_REACHED = "Target reached before picks locked";

    private final JdbcTemplate jdbc;
    private final PlayerStatsLookup stats;
    private final ObjectMapper json;

    PlayerLockRules(JdbcTemplate jdbc, PlayerStatsLookup stats, ObjectMapper json) {
        this.jdbc = jdbc;
        this.stats = stats;
        this.json = json;
    }

    @Override
    public Optional<String> washAtLock(UUID betId, String mode) {
        if (!mode.equals("prop_hunt") && !mode.equals("king_of_the_hill")) return Optional.empty();
        var row = jdbc.queryForMap("SELECT game_id, config::text AS config FROM bets WHERE id = ?", betId);
        String gameId = (String) row.get("game_id");
        JsonNode config = read((String) row.get("config"));
        String stat = config.path("stat").asText();
        BigDecimal line = config.path("line").decimalValue();
        for (JsonNode id : config.path("players")) {
            int value = stats.player(gameId, id.asText()).map(player -> player.stat(stat)).orElse(0);
            if (BigDecimal.valueOf(value).compareTo(line) >= 0) {
                return Optional.of(mode.equals("prop_hunt") ? LINE_REACHED : TARGET_REACHED);
            }
        }
        return Optional.empty();
    }

    private JsonNode read(String serialized) {
        try {
            return json.readTree(serialized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read bet", exception);
        }
    }
}
