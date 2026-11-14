package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One player's whole-game line in canonical stat keys (ADR 0005). Every provider maps into these keys; the player
 * modes read only them. {@code played} decides player-prop washes: an NBA player played with any minutes; an NFL
 * player played when the provider lists a row for them (it lists only players who recorded something).
 */
public record StatLine(League league, String gameProviderId, FeedPlayer player, boolean played,
                       Map<String, Integer> stats) {
    /** Canonical stats per league, in the order the proposal sheet offers them. */
    public static final Map<League, List<Stat>> STATS = Map.of(
            League.NBA, List.of(new Stat("points", "Points"), new Stat("rebounds", "Rebounds"),
                    new Stat("assists", "Assists"), new Stat("threes", "3-Pointers Made"),
                    new Stat("stocks", "Steals + Blocks")),
            League.NFL, List.of(new Stat("passing_yards", "Passing Yards"),
                    new Stat("rushing_yards", "Rushing Yards"), new Stat("receiving_yards", "Receiving Yards"),
                    new Stat("receptions", "Receptions"), new Stat("touchdowns", "Touchdowns")));

    public record Stat(String key, String label) {}

    public StatLine {
        Objects.requireNonNull(league, "league");
        Objects.requireNonNull(player, "player");
        stats = Map.copyOf(stats);
    }

    /** NBA row from {@code /v1/stats}; null when the player or game can't be read. */
    static StatLine nba(JsonNode row) {
        FeedPlayer player = FeedPlayer.from(League.NBA, row.path("player"), row.path("team"));
        String game = row.path("game").path("id").asText("");
        if (player == null || game.isEmpty()) return null;
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("points", count(row, "pts"));
        stats.put("rebounds", count(row, "reb"));
        stats.put("assists", count(row, "ast"));
        stats.put("threes", count(row, "fg3m"));
        stats.put("stocks", count(row, "stl") + count(row, "blk"));
        return new StatLine(League.NBA, game, player, minutesPlayed(row.path("min").asText("")), stats);
    }

    /**
     * NFL row from {@code /nfl/v1/stats}. The provider sends null for stats a player didn't record (a known
     * deviation from its spec), which count as zero. "Touchdowns" are the player's own rushing and receiving
     * touchdowns; passing touchdowns are credited to the receiver's score, not the passer's.
     */
    static StatLine nfl(JsonNode row) {
        FeedPlayer player = FeedPlayer.from(League.NFL, row.path("player"), row.path("team"));
        String game = row.path("game").path("id").asText("");
        if (player == null || game.isEmpty()) return null;
        Map<String, Integer> stats = new LinkedHashMap<>();
        stats.put("passing_yards", count(row, "passing_yards"));
        stats.put("rushing_yards", count(row, "rushing_yards"));
        stats.put("receiving_yards", count(row, "receiving_yards"));
        stats.put("receptions", count(row, "receptions"));
        stats.put("touchdowns", count(row, "rushing_touchdowns") + count(row, "receiving_touchdowns"));
        return new StatLine(League.NFL, game, player, true, stats);
    }

    /** A whole-number stat; null or missing is zero. Yards can be negative (a loss). */
    private static int count(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isNumber() ? value.asInt() : 0;
    }

    /** NBA minutes as "MM:SS", "MM" or blank; any time on the court counts. */
    static boolean minutesPlayed(String minutes) {
        String trimmed = minutes == null ? "" : minutes.trim();
        if (trimmed.isEmpty()) return false;
        for (char c : trimmed.toCharArray()) {
            if (c >= '1' && c <= '9') return true;
        }
        return false;
    }
}
