package com.p2picks.app.sports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores players and player lines (ADR 0005); a changed line also gets a history snapshot. */
@Service
public class StatsSync {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    StatsSync(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Upserts a game's player lines; returns how many changed. */
    @Transactional
    public int recordGame(String gameId, List<StatLine> lines, Instant observedAt) {
        Map<String, Previous> previous = new HashMap<>();
        jdbc.query("SELECT player_id, played, stats::text AS stats FROM player_game_stats WHERE game_id = ?", rs -> {
            previous.put(rs.getString("player_id"), new Previous(rs.getBoolean("played"), read(rs.getString("stats"))));
        }, gameId);
        int changed = 0;
        for (StatLine line : lines) {
            upsertPlayer(line.player(), observedAt, false);
            if (new Previous(line.played(), line.stats()).equals(previous.get(line.player().id()))) continue;
            String stats = write(line.stats());
            changed++;
            jdbc.update("""
                    INSERT INTO player_game_stats (game_id, player_id, team_abbreviation, played, stats, observed_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (game_id, player_id) DO UPDATE SET team_abbreviation = EXCLUDED.team_abbreviation,
                        played = EXCLUDED.played, stats = EXCLUDED.stats, observed_at = EXCLUDED.observed_at
                    """, gameId, line.player().id(), line.player().teamAbbreviation(), line.played(), stats,
                    Timestamp.from(observedAt));
            jdbc.update("""
                    INSERT INTO player_stat_snapshots (game_id, player_id, observed_at, played, stats)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, gameId, line.player().id(), Timestamp.from(observedAt), line.played(), stats);
        }
        return changed;
    }

    /** Upserts one page of the active list; {@code finished} marks the league refreshed and retires stale players. */
    @Transactional
    public void recordActivePlayers(League league, List<FeedPlayer> players, Instant refreshStartedAt, Instant now,
            boolean finished) {
        for (FeedPlayer player : players) upsertPlayer(player, now, true);
        if (!finished) return;
        // Anyone not seen since this refresh started has left the active list.
        jdbc.update("UPDATE players SET active = false WHERE league = ? AND updated_at < ?", league.name(),
                Timestamp.from(refreshStartedAt));
        jdbc.update("""
                INSERT INTO sports_feed_status (league, players_refreshed_at) VALUES (?, ?)
                ON CONFLICT (league) DO UPDATE SET players_refreshed_at = EXCLUDED.players_refreshed_at
                """, league.name(), Timestamp.from(now));
    }

    /** When the league's active list was last fully refreshed, or null. */
    public Instant playersRefreshedAt(League league) {
        List<Timestamp> at = jdbc.query("SELECT players_refreshed_at FROM sports_feed_status WHERE league = ?",
                (rs, row) -> rs.getTimestamp("players_refreshed_at"), league.name());
        return at.isEmpty() || at.getFirst() == null ? null : at.getFirst().toInstant();
    }

    /**
     * Stores season averages for known players (by provider id; unknown ones are skipped), and marks the other
     * {@code checked} players (our ids) as checked too, keeping what they had, so they aren't asked for again today.
     */
    public void recordSeasonAverages(League league, int season, List<SeasonAverage> averages, List<String> checked,
            Instant now) {
        for (String id : checked) {
            jdbc.update("""
                    INSERT INTO player_season_averages (player_id, season, games, averages, refreshed_at)
                    VALUES (?, ?, 0, '{}'::jsonb, ?)
                    ON CONFLICT (player_id) DO UPDATE SET refreshed_at = EXCLUDED.refreshed_at
                    """, id, season, Timestamp.from(now));
        }
        for (SeasonAverage average : averages) {
            try {
                jdbc.update("""
                        INSERT INTO player_season_averages (player_id, season, games, averages, refreshed_at)
                        SELECT id, ?, ?, ?::jsonb, ? FROM players WHERE league = ? AND provider_id = ?
                        ON CONFLICT (player_id) DO UPDATE SET season = EXCLUDED.season, games = EXCLUDED.games,
                            averages = EXCLUDED.averages, refreshed_at = EXCLUDED.refreshed_at
                        """, season, average.games(), json.writeValueAsString(average.averages()), Timestamp.from(now),
                        league.name(), average.providerId());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private void upsertPlayer(FeedPlayer player, Instant now, boolean fromActiveList) {
        jdbc.update("""
                INSERT INTO players (id, league, provider_id, first_name, last_name, position, team_abbreviation,
                                     active, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)
                ON CONFLICT (id) DO UPDATE SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                    position = COALESCE(EXCLUDED.position, players.position),
                    team_abbreviation = COALESCE(EXCLUDED.team_abbreviation, players.team_abbreviation),
                    active = true, updated_at = EXCLUDED.updated_at
                """, player.id(), player.league().name(), player.providerId(), player.firstName(),
                player.lastName(), player.position(), player.teamAbbreviation(), Timestamp.from(now));
    }

    private record Previous(boolean played, Map<String, Integer> stats) {}

    private Map<String, Integer> read(String stats) {
        try {
            return json.readValue(stats, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String write(Map<String, Integer> stats) {
        try {
            return json.writeValueAsString(new java.util.TreeMap<>(stats));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
