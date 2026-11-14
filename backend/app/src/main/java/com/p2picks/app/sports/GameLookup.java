package com.p2picks.app.sports;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only access to stored games for other modules (sports bets). */
@Service
public class GameLookup {
    private final JdbcTemplate jdbc;

    GameLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A game as last reported. {@code providerState} tells apart what {@code status} merges (suspended versus
     * canceled); {@code finalAt} is when the feed first reported Final; scores are null before the start.
     */
    public record Game(String id, League league, GameStatus status, String providerState, Instant startsAt,
                       Instant finalAt, Team home, Team away) {}

    public record Team(String abbreviation, Integer score, List<Integer> periods) {
        /** Points at the end of regulation, or null until all four periods are known. */
        public Integer regulationScore() {
            return new FeedGame.Team(abbreviation, "", "", score, periods).regulationScore();
        }
    }

    public Optional<Game> find(String gameId) {
        List<Game> games = jdbc.query("""
                SELECT id, league, status, provider_state, starts_at, final_at, home_abbreviation, home_score,
                       home_periods, away_abbreviation, away_score, away_periods
                FROM games WHERE id = ?
                """, (rs, row) -> new Game(rs.getString("id"), League.valueOf(rs.getString("league")),
                GameStatus.valueOf(rs.getString("status")), rs.getString("provider_state"),
                rs.getTimestamp("starts_at").toInstant(), instant(rs.getTimestamp("final_at")),
                new Team(rs.getString("home_abbreviation"), (Integer) rs.getObject("home_score"),
                        periods(rs.getArray("home_periods"))),
                new Team(rs.getString("away_abbreviation"), (Integer) rs.getObject("away_score"),
                        periods(rs.getArray("away_periods")))), gameId);
        return games.stream().findFirst();
    }

    /**
     * Since when the game has been continuously reported suspended (the earliest snapshot of its current
     * suspension), or null when it isn't suspended now.
     */
    public Instant suspendedSince(String gameId) {
        List<Timestamp> since = jdbc.query("""
                SELECT min(s.observed_at) AS since FROM game_snapshots s
                WHERE s.game_id = ? AND s.provider_state = 'suspended'
                  AND s.observed_at > COALESCE((SELECT max(o.observed_at) FROM game_snapshots o
                                                 WHERE o.game_id = s.game_id
                                                   AND o.provider_state IS DISTINCT FROM 'suspended'),
                                                '-infinity'::timestamptz)
                """, (rs, row) -> rs.getTimestamp("since"), gameId);
        return since.isEmpty() ? null : instant(since.getFirst());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<Integer> periods(java.sql.Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((Integer[]) array.getArray());
    }
}
