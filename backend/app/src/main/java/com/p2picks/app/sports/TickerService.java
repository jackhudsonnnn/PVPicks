package com.p2picks.app.sports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The sports-only ticker: games scheduled on the current New York date plus live games from earlier dates, live
 * first, then by start and ID. Unavailable unless every league polled successfully within the freshness window,
 * so an outage is never shown as "no games".
 */
@Component
class TickerService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration freshness;

    TickerService(JdbcTemplate jdbc, Clock clock, @Value("${pvpicks.sports.freshness:PT3M}") Duration freshness) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.freshness = freshness;
    }

    Optional<List<TickerGame>> ticker() {
        Instant now = clock.instant();
        Integer fresh = jdbc.queryForObject(
                "SELECT count(*) FROM sports_feed_status WHERE last_success_at >= ?",
                Integer.class, Timestamp.from(now.minus(freshness)));
        if (fresh == null || fresh < League.values().length) return Optional.empty();
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of(GameSync.NEW_YORK));
        return Optional.of(jdbc.query("""
                SELECT * FROM games
                WHERE (starts_at AT TIME ZONE '%1$s')::date = ?
                   OR (status = 'LIVE' AND (starts_at AT TIME ZONE '%1$s')::date < ?)
                ORDER BY status = 'LIVE' DESC, starts_at, id
                """.formatted(GameSync.NEW_YORK), (rs, row) -> game(rs), today, today));
    }

    private static TickerGame game(ResultSet rs) throws SQLException {
        return new TickerGame(rs.getString("id"), rs.getString("league"), rs.getString("status"),
                rs.getTimestamp("starts_at").toInstant().toString(), (Integer) rs.getObject("period"),
                rs.getString("clock"),
                new TickerGame.Team(rs.getString("home_abbreviation"), (Integer) rs.getObject("home_score")),
                new TickerGame.Team(rs.getString("away_abbreviation"), (Integer) rs.getObject("away_score")),
                rs.getTimestamp("observed_at").toInstant().toString());
    }

    /** The OpenAPI TickerGame: no bet, table, participant or financial data. */
    record TickerGame(String id, String league, String status, String startsAt, Integer period, String clock,
                      Team home, Team away, String observedAt) {
        record Team(String abbreviation, Integer score) {}
    }
}
