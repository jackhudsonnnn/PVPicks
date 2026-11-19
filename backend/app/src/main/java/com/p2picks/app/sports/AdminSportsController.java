package com.p2picks.app.sports;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin sports explorer: leagues with feed status, the days that have games, a day's games, and one game in
 * full (latest state, per-period scores, every player's stats) with its snapshot history and each player's stat
 * history. Days are New York dates, the same calendar the poller uses. Read-only; {@code /admin/**} is admin-only.
 */
@RestController
class AdminSportsController {
    static final int DAY_LIMIT = 400;
    static final int SNAPSHOT_LIMIT = 5000;

    record LeagueRow(String league, int games, int live, LocalDate firstDay, LocalDate lastDay, Instant lastSuccessAt,
            Instant lastFailureAt, String lastFailure) {}

    record DayRow(LocalDate date, int games, int live, int finals) {}

    record Team(String abbreviation, String name, String city, Integer score, List<Integer> periods) {}

    record GameRow(String id, String league, String providerId, String status, Instant startsAt, Integer period,
            String clock, Team away, Team home, Instant observedAt, int snapshots, int bets) {}

    record PlayerRow(String id, String name, String position, String team, boolean played, JsonNode stats,
            Instant observedAt, int snapshots) {}

    record BetCount(String mode, String status, int count) {}

    record Game(GameRow game, Instant firstObservedAt, List<PlayerRow> players, List<BetCount> bets) {}

    record Snapshot(Instant observedAt, String status, String providerState, Integer period, String clock,
            Integer homeScore, Integer awayScore, List<Integer> homePeriods, List<Integer> awayPeriods) {}

    record StatSnapshot(Instant observedAt, boolean played, JsonNode stats) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    AdminSportsController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/admin/sports/leagues")
    ResponseEntity<List<LeagueRow>> leagues() {
        return ok(jdbc.query("""
                SELECT l.league, count(g.id) AS games, count(g.id) FILTER (WHERE g.status = 'LIVE') AS live,
                       min((g.starts_at AT TIME ZONE 'America/New_York')::date) AS first_day,
                       max((g.starts_at AT TIME ZONE 'America/New_York')::date) AS last_day,
                       s.last_success_at, s.last_failure_at, s.last_failure
                FROM (VALUES ('NBA'), ('NFL')) AS l(league)
                LEFT JOIN games g ON g.league = l.league
                LEFT JOIN sports_feed_status s ON s.league = l.league
                GROUP BY l.league, s.last_success_at, s.last_failure_at, s.last_failure
                ORDER BY l.league
                """, (rs, row) -> new LeagueRow(rs.getString("league"), rs.getInt("games"), rs.getInt("live"),
                        rs.getObject("first_day", LocalDate.class), rs.getObject("last_day", LocalDate.class),
                        instant(rs, "last_success_at"), instant(rs, "last_failure_at"), rs.getString("last_failure"))));
    }

    @GetMapping("/admin/sports/days")
    ResponseEntity<?> days(@RequestParam String league) {
        if (!validLeague(league)) return error(400, "bad_request", "league is NBA or NFL.");
        return ok(jdbc.query("""
                SELECT (starts_at AT TIME ZONE 'America/New_York')::date AS day, count(*) AS games,
                       count(*) FILTER (WHERE status = 'LIVE') AS live,
                       count(*) FILTER (WHERE status = 'FINAL') AS finals
                FROM games WHERE league = ? GROUP BY 1 ORDER BY 1 DESC LIMIT ?
                """, (rs, row) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getInt("games"),
                        rs.getInt("live"), rs.getInt("finals")), league, DAY_LIMIT));
    }

    @GetMapping("/admin/sports/games")
    ResponseEntity<?> games(@RequestParam String league, @RequestParam LocalDate date) {
        if (!validLeague(league)) return error(400, "bad_request", "league is NBA or NFL.");
        return ok(jdbc.query(GAME_SELECT + """
                WHERE g.league = ? AND (g.starts_at AT TIME ZONE 'America/New_York')::date = ?
                ORDER BY g.starts_at, g.id
                """, (rs, row) -> game(rs), league, date));
    }

    @GetMapping("/admin/sports/games/{gameId}")
    ResponseEntity<?> game(@PathVariable String gameId) {
        List<GameRow> found = jdbc.query(GAME_SELECT + "WHERE g.id = ?", (rs, row) -> game(rs), gameId);
        if (found.isEmpty()) return error(404, "not_found", "No such game.");
        Instant first = jdbc.queryForObject("SELECT min(observed_at) FROM game_snapshots WHERE game_id = ?",
                (rs, row) -> instant(rs, "min"), gameId);
        List<PlayerRow> players = jdbc.query("""
                SELECT p.id, p.first_name || ' ' || p.last_name AS name, p.position,
                       COALESCE(s.team_abbreviation, p.team_abbreviation) AS team, s.played, s.stats::text AS stats,
                       s.observed_at,
                       (SELECT count(*) FROM player_stat_snapshots x WHERE x.game_id = s.game_id
                          AND x.player_id = s.player_id) AS snapshots
                FROM player_game_stats s JOIN players p ON p.id = s.player_id
                WHERE s.game_id = ? ORDER BY team, p.last_name, p.first_name
                """, (rs, row) -> new PlayerRow(rs.getString("id"), rs.getString("name"), rs.getString("position"),
                        rs.getString("team"), rs.getBoolean("played"), tree(rs.getString("stats")),
                        instant(rs, "observed_at"), rs.getInt("snapshots")), gameId);
        List<BetCount> bets = jdbc.query("""
                SELECT mode, status, count(*) AS n FROM bets WHERE game_id = ? GROUP BY mode, status ORDER BY mode, status
                """, (rs, row) -> new BetCount(rs.getString("mode"), rs.getString("status"), rs.getInt("n")), gameId);
        return ok(new Game(found.getFirst(), first, players, bets));
    }

    /** The game's snapshots, oldest first (one per change the poller saw), up to {@value #SNAPSHOT_LIMIT}. */
    @GetMapping("/admin/sports/games/{gameId}/snapshots")
    ResponseEntity<?> snapshots(@PathVariable String gameId) {
        if (!exists(gameId)) return error(404, "not_found", "No such game.");
        return ok(jdbc.query("""
                SELECT * FROM (
                    SELECT observed_at, status, provider_state, period, clock, home_score, away_score,
                           home_periods, away_periods, id
                    FROM game_snapshots WHERE game_id = ? ORDER BY observed_at DESC, id DESC LIMIT ?
                ) latest ORDER BY observed_at, id
                """, (rs, row) -> new Snapshot(instant(rs, "observed_at"), rs.getString("status"),
                        rs.getString("provider_state"), (Integer) rs.getObject("period", Integer.class),
                        rs.getString("clock"), (Integer) rs.getObject("home_score", Integer.class),
                        (Integer) rs.getObject("away_score", Integer.class), ints(rs.getArray("home_periods")),
                        ints(rs.getArray("away_periods"))), gameId, SNAPSHOT_LIMIT));
    }

    /** One player's stat snapshots in a game, oldest first. */
    @GetMapping("/admin/sports/games/{gameId}/players/{playerId}/snapshots")
    ResponseEntity<?> playerSnapshots(@PathVariable String gameId, @PathVariable String playerId) {
        if (!exists(gameId)) return error(404, "not_found", "No such game.");
        return ok(jdbc.query("""
                SELECT * FROM (
                    SELECT observed_at, played, stats::text AS stats, id FROM player_stat_snapshots
                    WHERE game_id = ? AND player_id = ? ORDER BY observed_at DESC, id DESC LIMIT ?
                ) latest ORDER BY observed_at, id
                """, (rs, row) -> new StatSnapshot(instant(rs, "observed_at"), rs.getBoolean("played"),
                        tree(rs.getString("stats"))), gameId, playerId, SNAPSHOT_LIMIT));
    }

    private static final String GAME_SELECT = """
            SELECT g.*,
                   (SELECT count(*) FROM game_snapshots s WHERE s.game_id = g.id) AS snapshots,
                   (SELECT count(*) FROM bets b WHERE b.game_id = g.id) AS bets
            FROM games g
            """;

    private GameRow game(ResultSet rs) throws SQLException {
        return new GameRow(rs.getString("id"), rs.getString("league"), rs.getString("provider_id"),
                rs.getString("status"), instant(rs, "starts_at"), (Integer) rs.getObject("period", Integer.class),
                rs.getString("clock"),
                new Team(rs.getString("away_abbreviation"), rs.getString("away_name"), rs.getString("away_city"),
                        (Integer) rs.getObject("away_score", Integer.class), ints(rs.getArray("away_periods"))),
                new Team(rs.getString("home_abbreviation"), rs.getString("home_name"), rs.getString("home_city"),
                        (Integer) rs.getObject("home_score", Integer.class), ints(rs.getArray("home_periods"))),
                instant(rs, "observed_at"), rs.getInt("snapshots"), rs.getInt("bets"));
    }

    private boolean exists(String gameId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM games WHERE id = ?)",
                Boolean.class, gameId));
    }

    private static boolean validLeague(String league) {
        return Arrays.stream(League.values()).anyMatch(value -> value.name().equals(league));
    }

    private JsonNode tree(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored stats are not JSON", exception);
        }
    }

    private static List<Integer> ints(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((Integer[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
