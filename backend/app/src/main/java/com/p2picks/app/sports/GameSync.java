package com.p2picks.app.sports;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    /** Upserts the league's games and marks the feed healthy; returns how many games changed. */
    @Transactional
    public int record(League league, List<FeedGame> games, Instant observedAt) {
        Map<String, FeedGame> previous = previous(games);
        int changed = 0;
        for (FeedGame game : games) {
            if (game.league() != league) throw new IllegalArgumentException("Game from another league");
            if (!game.equals(previous.get(game.id()))) changed++;
            jdbc.update("""
                    INSERT INTO games (id, league, provider_id, status, starts_at, period, clock,
                        home_abbreviation, home_name, home_city, home_score,
                        away_abbreviation, away_name, away_city, away_score, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, starts_at = EXCLUDED.starts_at,
                        period = EXCLUDED.period, clock = EXCLUDED.clock,
                        home_abbreviation = EXCLUDED.home_abbreviation, home_name = EXCLUDED.home_name,
                        home_city = EXCLUDED.home_city, home_score = EXCLUDED.home_score,
                        away_abbreviation = EXCLUDED.away_abbreviation, away_name = EXCLUDED.away_name,
                        away_city = EXCLUDED.away_city, away_score = EXCLUDED.away_score,
                        observed_at = EXCLUDED.observed_at
                    """, game.id(), league.name(), game.providerId(), game.status().name(),
                    Timestamp.from(game.startsAt()), game.period(), game.clock(),
                    game.home().abbreviation(), game.home().name(), game.home().city(), game.home().score(),
                    game.away().abbreviation(), game.away().name(), game.away().city(), game.away().score(),
                    Timestamp.from(observedAt));
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

    /** Whether a game scheduled on that New York date may still change (so the date is worth polling). */
    boolean hasUnfinishedGames(League league, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM games WHERE league = ? AND status IN ('SCHEDULED', 'LIVE')
                    AND (starts_at AT TIME ZONE '""" + NEW_YORK + "')::date = ?)",
                Boolean.class, league.name(), date));
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
                            rs.getString("home_city"), (Integer) rs.getObject("home_score")),
                    new FeedGame.Team(rs.getString("away_abbreviation"), rs.getString("away_name"),
                            rs.getString("away_city"), (Integer) rs.getObject("away_score")));
            previous.put(Objects.requireNonNull(rs.getString("id")), game);
        });
        return previous;
    }
}
