package com.p2picks.app.sports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only access to stored players, per-game stat lines and their history, and a game's score history, for the
 * player modes and Scorcerer (bets module). Stats are canonical keys ({@link StatLine#STATS}); a missing key is 0.
 */
@Service
public class PlayerStatsLookup {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    PlayerStatsLookup(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * A player who can be picked for a game: on either team's active list (real games), or seen in the game's
     * stats.
     * {@code stats} and {@code played} are the current line (empty and false before the player shows up in the
     * feed).
     */
    public record GamePlayer(String id, String firstName, String lastName, String position, String team,
                             boolean played, Map<String, Integer> stats) {
        public String name() {
            return (firstName + " " + lastName).trim();
        }

        public int stat(String key) {
            return stats.getOrDefault(key, 0);
        }
    }

    /** One stored score update: when it was observed, the period, and both scores. */
    public record ScoreUpdate(Instant observedAt, Integer period, int home, int away) {}

    /** Whether {@code key} is a stat the modes can use in that league. */
    public static boolean isStat(League league, String key) {
        return StatLine.STATS.get(league).stream().anyMatch(stat -> stat.key().equals(key));
    }

    /** The label for a stat key, e.g. "Points". */
    public static String label(League league, String key) {
        return StatLine.STATS.get(league).stream().filter(stat -> stat.key().equals(key)).findFirst()
                .map(StatLine.Stat::label).orElse(key);
    }

    /** Both teams' players for a game, by team then name. */
    public List<GamePlayer> players(String gameId) {
        return jdbc.query("""
                SELECT p.id, p.first_name, p.last_name, p.position,
                       coalesce(s.team_abbreviation, p.team_abbreviation) AS team,
                       coalesce(s.played, false) AS played, coalesce(s.stats, '{}'::jsonb)::text AS stats
                FROM games g
                JOIN players p ON p.league = g.league
                LEFT JOIN player_game_stats s ON s.game_id = g.id AND s.player_id = p.id
                WHERE g.id = ?
                  AND (s.player_id IS NOT NULL
                       OR (p.active AND p.team_abbreviation IN (g.home_abbreviation, g.away_abbreviation)
                           -- Dev games share team abbreviations with real teams, but only their own players play.
                           AND g.provider_id NOT LIKE 'DEV-%'))
                ORDER BY team, p.last_name, p.first_name, p.id
                """, (rs, row) -> new GamePlayer(rs.getString("id"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("position"), rs.getString("team"), rs.getBoolean("played"),
                stats(rs.getString("stats"))), gameId);
    }

    /** Per-game season averages by stat key, for the players that have any (missing players have none). */
    public Map<String, Map<String, Double>> seasonAverages(List<String> playerIds) {
        if (playerIds.isEmpty()) return Map.of();
        Map<String, Map<String, Double>> averages = new java.util.HashMap<>();
        String ids = "{" + String.join(",", playerIds) + "}";
        jdbc.query("""
                SELECT player_id, averages::text AS averages FROM player_season_averages
                WHERE player_id = ANY (?::text[]) AND games > 0
                """, rs -> {
                    try {
                        averages.put(rs.getString("player_id"), json.readValue(rs.getString("averages"),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {}));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                        throw new IllegalStateException(exception);
                    }
                }, ids);
        return averages;
    }

    /** One player's current line in a game, if they're pickable for it. */
    public Optional<GamePlayer> player(String gameId, String playerId) {
        return players(gameId).stream().filter(player -> player.id().equals(playerId)).findFirst();
    }

    /** When the game was first seen in overtime (period 5 or later), or null if it never was. */
    public Instant overtimeStart(String gameId) {
        List<Timestamp> start = jdbc.query("""
                SELECT min(observed_at) FROM game_snapshots WHERE game_id = ? AND period >= 5
                """, (rs, row) -> rs.getTimestamp(1), gameId);
        return start.isEmpty() || start.getFirst() == null ? null : start.getFirst().toInstant();
    }

    /** A player's stat as last observed before {@code cutoff}: 0 when there's no earlier observation. */
    public int valueBefore(String gameId, String playerId, String stat, Instant cutoff) {
        List<Integer> values = jdbc.query("""
                SELECT coalesce((stats ->> ?)::int, 0) FROM player_stat_snapshots
                WHERE game_id = ? AND player_id = ? AND observed_at < ?
                ORDER BY observed_at DESC, id DESC LIMIT 1
                """, (rs, row) -> rs.getInt(1), stat, gameId, playerId, Timestamp.from(cutoff));
        return values.isEmpty() ? 0 : values.getFirst();
    }

    /** When the player's stat was first observed at or above {@code target}, or null if it never was. */
    public Instant firstReached(String gameId, String playerId, String stat, int target) {
        List<Timestamp> at = jdbc.query("""
                SELECT min(observed_at) FROM player_stat_snapshots
                WHERE game_id = ? AND player_id = ? AND coalesce((stats ->> ?)::int, 0) >= ?
                """, (rs, row) -> rs.getTimestamp(1), gameId, playerId, stat, target);
        return at.isEmpty() || at.getFirst() == null ? null : at.getFirst().toInstant();
    }

    /**
     * One team's scoring plays as its players' stat lines count them: the two kinds Scorcerer bets on ({@code big}
     * and {@code small}) and the one-pointers it ignores. NFL: touchdowns, field goals, extra points. NBA: 3-pointers,
     * 2-pointers, free throws.
     */
    public record TeamScoring(int big, int small, int ones) {
        public static final TeamScoring NONE = new TeamScoring(0, 0, 0);

        /** The points these plays explain. NFL 2-point plays (safeties, conversions) aren't in the feed. */
        public int points(League league) {
            return league == League.NFL ? 6 * big + 3 * small + ones : 3 * big + 2 * small + ones;
        }
    }

    /** Both teams' scoring totals as of one stats observation. */
    public record ScoringTotals(Instant observedAt, TeamScoring home, TeamScoring away) {}

    /** The stat keys behind {@link TeamScoring}'s big, small and ones, per league. */
    private static List<String> scoringKeys(League league) {
        return league == League.NFL ? List.of("touchdowns", "field_goals", "extra_points")
                : List.of("threes", "twos", "free_throws");
    }

    /**
     * Each team's scoring plays, summed over its players' lines, after every stats observation that changed one
     * (oldest first). A player without a line counts as 0.
     */
    public List<ScoringTotals> scoringTotals(String gameId, League league, String homeAbbreviation,
            String awayAbbreviation) {
        List<String> keys = scoringKeys(league);
        Map<String, String> teams = new java.util.HashMap<>();
        Map<String, Map<String, Integer>> lines = new java.util.HashMap<>();
        List<ScoringTotals> totals = new java.util.ArrayList<>();
        Instant[] current = {null};
        jdbc.query("""
                SELECT s.observed_at, s.player_id, g.team_abbreviation, s.stats::text AS stats
                FROM player_stat_snapshots s
                JOIN player_game_stats g ON g.game_id = s.game_id AND g.player_id = s.player_id
                WHERE s.game_id = ? ORDER BY s.observed_at, s.id
                """, rs -> {
            Instant at = rs.getTimestamp("observed_at").toInstant();
            if (current[0] != null && !at.equals(current[0])) {
                totals.add(sum(current[0], keys, teams, lines, homeAbbreviation, awayAbbreviation));
            }
            current[0] = at;
            String player = rs.getString("player_id");
            teams.put(player, rs.getString("team_abbreviation"));
            lines.put(player, stats(rs.getString("stats")));
        }, gameId);
        if (current[0] != null) totals.add(sum(current[0], keys, teams, lines, homeAbbreviation, awayAbbreviation));
        return List.copyOf(totals);
    }

    private static ScoringTotals sum(Instant at, List<String> keys, Map<String, String> teams,
            Map<String, Map<String, Integer>> lines, String home, String away) {
        int[][] counts = new int[2][3];
        lines.forEach((player, line) -> {
            String team = teams.get(player);
            int side = home.equals(team) ? 0 : away.equals(team) ? 1 : -1;
            if (side < 0) return;
            for (int k = 0; k < 3; k++) counts[side][k] += line.getOrDefault(keys.get(k), 0);
        });
        return new ScoringTotals(at, new TeamScoring(counts[0][0], counts[0][1], counts[0][2]),
                new TeamScoring(counts[1][0], counts[1][1], counts[1][2]));
    }

    /** Every stored score update of the game, oldest first (one per change). */
    public List<ScoreUpdate> scores(String gameId) {
        return jdbc.query("""
                SELECT observed_at, period, coalesce(home_score, 0) AS home, coalesce(away_score, 0) AS away
                FROM game_snapshots WHERE game_id = ? ORDER BY observed_at, id
                """, (rs, row) -> new ScoreUpdate(rs.getTimestamp("observed_at").toInstant(),
                (Integer) rs.getObject("period"), rs.getInt("home"), rs.getInt("away")), gameId);
    }

    private Map<String, Integer> stats(String serialized) {
        try {
            return json.readValue(serialized, new TypeReference<Map<String, Integer>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable stats", exception);
        }
    }
}
