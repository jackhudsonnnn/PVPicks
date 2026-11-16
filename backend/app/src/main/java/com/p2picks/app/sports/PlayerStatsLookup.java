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
