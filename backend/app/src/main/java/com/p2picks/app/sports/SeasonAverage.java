package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One player's per-game averages for a season, in the modes' stat keys ({@link StatLine#STATS}), from the provider's
 * season data: NFL season stats (totals divided by games played) or NBA season averages. Unreadable rows are null.
 */
record SeasonAverage(String providerId, int games, Map<String, Double> averages) {

    /** The season a date falls in: both leagues' seasons start in the fall and are named by their first year. */
    static int seasonOf(LocalDate date) {
        return date.getMonthValue() >= 8 ? date.getYear() : date.getYear() - 1;
    }

    /** An NFL {@code season_stats} row. */
    static SeasonAverage nfl(JsonNode row) {
        String id = row.path("player").path("id").asText("");
        int games = row.path("games_played").asInt(0);
        if (id.isBlank() || games <= 0) return null;
        Map<String, Double> averages = new LinkedHashMap<>();
        averages.put("passing_yards", per(row, "passing_yards", games));
        averages.put("rushing_yards", per(row, "rushing_yards", games));
        averages.put("receiving_yards", per(row, "receiving_yards", games));
        averages.put("receptions", per(row, "receptions", games));
        averages.put("touchdowns", round((row.path("rushing_touchdowns").asDouble(0)
                + row.path("receiving_touchdowns").asDouble(0)) / games));
        return new SeasonAverage(id, games, averages);
    }

    /** An NBA {@code season_averages/general} row: the averages are under {@code stats} (or on the row itself). */
    static SeasonAverage nba(JsonNode row) {
        String id = row.path("player").path("id").asText(row.path("player_id").asText(""));
        JsonNode stats = row.has("stats") ? row.path("stats") : row;
        int games = stats.path("gp").asInt(stats.path("games_played").asInt(0));
        if (id.isBlank() || games <= 0) return null;
        Map<String, Double> averages = new LinkedHashMap<>();
        averages.put("points", round(stats.path("pts").asDouble(0)));
        averages.put("rebounds", round(stats.path("reb").asDouble(0)));
        averages.put("assists", round(stats.path("ast").asDouble(0)));
        averages.put("threes", round(stats.path("fg3m").asDouble(0)));
        averages.put("stocks", round(stats.path("stl").asDouble(0) + stats.path("blk").asDouble(0)));
        return new SeasonAverage(id, games, averages);
    }

    private static double per(JsonNode row, String field, int games) {
        return round(row.path(field).asDouble(0) / games);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
