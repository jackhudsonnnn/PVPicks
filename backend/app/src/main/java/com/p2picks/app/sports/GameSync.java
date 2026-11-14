package com.p2picks.app.sports;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores feed snapshots and feed health, and records one outbox event per poll that changed a game. */
@Component
class GameSync {
    static final String NEW_YORK = "America/New_York";

    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    GameSync(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /**
     * Upserts the league's games, appends a history snapshot for each game that changed (ADR 0005), and marks the feed
     * healthy; returns how many games changed.
     */
    @Transactional
    public int record(League league, List<FeedGame> games, Instant observedAt) {
        Map<String, FeedGame> previous = previous(games);
        int changed = 0;
        for (FeedGame game : games) {
            if (game.league() != league) throw new IllegalArgumentException("Game from another league");
            boolean isChanged = !game.equals(previous.get(game.id()));
            if (isChanged) changed++;
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO games (id, league, provider_id, status, starts_at, period, clock,
                            home_abbreviation, home_name, home_city, home_score,
                            away_abbreviation, away_name, away_city, away_score, observed_at,
                            provider_state, home_periods, away_periods, final_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                CASE WHEN ? = 'FINAL' THEN ?::timestamptz END)
                        ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, starts_at = EXCLUDED.starts_at,
                            period = EXCLUDED.period, clock = EXCLUDED.clock,
                            home_abbreviation = EXCLUDED.home_abbreviation, home_name = EXCLUDED.home_name,
                            home_city = EXCLUDED.home_city, home_score = EXCLUDED.home_score,
                            away_abbreviation = EXCLUDED.away_abbreviation, away_name = EXCLUDED.away_name,
                            away_city = EXCLUDED.away_city, away_score = EXCLUDED.away_score,
                            observed_at = EXCLUDED.observed_at, provider_state = EXCLUDED.provider_state,
                            home_periods = EXCLUDED.home_periods, away_periods = EXCLUDED.away_periods,
                            final_at = CASE WHEN EXCLUDED.status = 'FINAL'
                                            THEN COALESCE(games.final_at, EXCLUDED.final_at) END
                        """);
                int i = 0;
                statement.setString(++i, game.id());
                statement.setString(++i, league.name());
                statement.setString(++i, game.providerId());
                statement.setString(++i, game.status().name());
                statement.setTimestamp(++i, Timestamp.from(game.startsAt()));
                statement.setObject(++i, game.period());
                statement.setString(++i, game.clock());
                statement.setString(++i, game.home().abbreviation());
                statement.setString(++i, game.home().name());
                statement.setString(++i, game.home().city());
                statement.setObject(++i, game.home().score());
                statement.setString(++i, game.away().abbreviation());
                statement.setString(++i, game.away().name());
                statement.setString(++i, game.away().city());
                statement.setObject(++i, game.away().score());
                statement.setTimestamp(++i, Timestamp.from(observedAt));
                statement.setString(++i, game.providerState());
                statement.setArray(++i, connection.createArrayOf("integer", game.home().periods().toArray()));
                statement.setArray(++i, connection.createArrayOf("integer", game.away().periods().toArray()));
                statement.setString(++i, game.status().name());
                statement.setTimestamp(++i, Timestamp.from(observedAt));
                return statement;
            });
            if (isChanged) {
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("""
                            INSERT INTO game_snapshots (game_id, observed_at, status, provider_state, period, clock,
                                home_score, away_score, home_periods, away_periods)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """);
                    statement.setString(1, game.id());
                    statement.setTimestamp(2, Timestamp.from(observedAt));
                    statement.setString(3, game.status().name());
                    statement.setString(4, game.providerState());
                    statement.setObject(5, game.period());
                    statement.setString(6, game.clock());
                    statement.setObject(7, game.home().score());
                    statement.setObject(8, game.away().score());
                    statement.setArray(9, connection.createArrayOf("integer", game.home().periods().toArray()));
                    statement.setArray(10, connection.createArrayOf("integer", game.away().periods().toArray()));
                    return statement;
                });
            }
        }
        jdbc.update("""
                INSERT INTO sports_feed_status (league, last_success_at) VALUES (?, ?)
                ON CONFLICT (league) DO UPDATE SET last_success_at = EXCLUDED.last_success_at
                """, league.name(), Timestamp.from(observedAt));
        if (changed > 0) {
            outbox.append(OutboxEvent.SPORTS_GAMES_CHANGED, league.name(),
                    Map.of("league", league.name(), "changed", changed));
        }
        return changed;
    }

    /** Records a failed poll without touching the last success, so freshness still decides availability. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(League league, String message, Instant at) {
        String reason = message == null ? "unknown" : message.substring(0, Math.min(500, message.length()));
        jdbc.update("""
                INSERT INTO sports_feed_status (league, last_failure_at, last_failure) VALUES (?, ?, ?)
                ON CONFLICT (league) DO UPDATE SET last_failure_at = EXCLUDED.last_failure_at,
                    last_failure = EXCLUDED.last_failure
                """, league.name(), Timestamp.from(at), reason);
    }


    private static List<Integer> periods(java.sql.Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((Integer[]) array.getArray());
    }

    private Map<String, FeedGame> previous(List<FeedGame> games) {
        Map<String, FeedGame> previous = new HashMap<>();
        if (games.isEmpty()) return previous;
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT * FROM games WHERE id = ANY(?)");
            statement.setArray(1, connection.createArrayOf("text", games.stream().map(FeedGame::id).toArray()));
            return statement;
        }, rs -> {
            FeedGame game = new FeedGame(League.valueOf(rs.getString("league")), rs.getString("provider_id"),
                    GameStatus.valueOf(rs.getString("status")), rs.getTimestamp("starts_at").toInstant(),
                    (Integer) rs.getObject("period"), rs.getString("clock"),
                    new FeedGame.Team(rs.getString("home_abbreviation"), rs.getString("home_name"),
                            rs.getString("home_city"), (Integer) rs.getObject("home_score"),
                            periods(rs.getArray("home_periods"))),
                    new FeedGame.Team(rs.getString("away_abbreviation"), rs.getString("away_name"),
                            rs.getString("away_city"), (Integer) rs.getObject("away_score"),
                            periods(rs.getArray("away_periods"))),
                    rs.getString("provider_state"));
            previous.put(Objects.requireNonNull(rs.getString("id")), game);
        });
        return previous;
    }
}
