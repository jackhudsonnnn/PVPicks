package com.p2picks.app.sports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Maps provider samples into the canonical schema (ADR 0004). */
class BallDontLieFeedTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsNbaScheduledLiveAndFinalGames() throws Exception {
        FeedGame scheduled = BallDontLieFeed.nba(json("""
                {"id": 101, "datetime": "2026-11-13T00:30:00Z", "status": "2026-11-13T00:30:00Z", "period": 0,
                 "time": null, "home_team_score": 0, "visitor_team_score": 0,
                 "home_team": {"abbreviation": "BOS", "city": "Boston", "name": "Celtics"},
                 "visitor_team": {"abbreviation": "NYK", "city": "New York", "name": "Knicks"}}
                """));
        assertThat(scheduled.status()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(scheduled.id()).isEqualTo("NBA-101");
        assertThat(scheduled.startsAt()).isEqualTo(Instant.parse("2026-11-13T00:30:00Z"));
        assertThat(scheduled.home().score()).isNull();
        assertThat(scheduled.period()).isNull();

        FeedGame live = BallDontLieFeed.nba(json("""
                {"id": 102, "datetime": "2026-11-13T00:00:00Z", "status": "3rd Qtr", "period": 3, "time": "5:32",
                 "home_team_score": 71, "visitor_team_score": 68,
                 "home_team": {"abbreviation": "LAL", "city": "Los Angeles", "name": "Lakers"},
                 "visitor_team": {"abbreviation": "GSW", "city": "Golden State", "name": "Warriors"}}
                """));
        assertThat(live.status()).isEqualTo(GameStatus.LIVE);
        assertThat(live.period()).isEqualTo(3);
        assertThat(live.clock()).isEqualTo("5:32");
        assertThat(live.home().score()).isEqualTo(71);

        FeedGame finished = BallDontLieFeed.nba(json("""
                {"id": 103, "datetime": "2026-11-12T23:00:00Z", "status": "Final", "period": 4, "time": "Final",
                 "home_team_score": 110, "visitor_team_score": 99,
                 "home_team": {"abbreviation": "MIA"}, "visitor_team": {"abbreviation": "ORL"}}
                """));
        assertThat(finished.status()).isEqualTo(GameStatus.FINAL);
        assertThat(finished.clock()).isNull();
        assertThat(finished.away().score()).isEqualTo(99);
    }

    @Test
    void usesStatusStateForTheLifecycleAndSkipsUnknownGames() throws Exception {
        assertThat(BallDontLieFeed.state("in_progress", "7:00 pm ET", 0)).isEqualTo(GameStatus.LIVE);
        assertThat(BallDontLieFeed.state("final", "4th Qtr", 4)).isEqualTo(GameStatus.FINAL);
        assertThat(BallDontLieFeed.state("delayed", "Delayed", 0)).isEqualTo(GameStatus.SCHEDULED);
        assertThat(BallDontLieFeed.state("canceled", "Canceled", 0)).isEqualTo(GameStatus.POSTPONED);
        assertThat(BallDontLieFeed.state("unknown", "Final", 4)).isNull();
        assertThat(BallDontLieFeed.state(null, "Final", 4)).as("label fallback").isEqualTo(GameStatus.FINAL);
        // Some historical overtime finals report period 4; the state still decides.
        FeedGame overtimeFinal = BallDontLieFeed.nba(json("""
                {"id": 9, "datetime": "2026-11-12T23:00:00Z", "status": "Final/OT", "status_state": "final",
                 "period": 4, "time": " ", "home_team_score": 120, "visitor_team_score": 118,
                 "home_team": {"abbreviation": "DEN"}, "visitor_team": {"abbreviation": "PHX"}}
                """));
        assertThat(overtimeFinal.status()).isEqualTo(GameStatus.FINAL);
        assertThat(BallDontLieFeed.nba(json("""
                {"id": 10, "datetime": "2026-11-12T23:00:00Z", "status": "??", "status_state": "unknown",
                 "home_team": {"abbreviation": "DEN"}, "visitor_team": {"abbreviation": "PHX"}}
                """))).isNull();
    }

    @Test
    void takesTheNflPeriodFromQuarterScoresBecauseThereIsNoClock() throws Exception {
        FeedGame live = BallDontLieFeed.nfl(json("""
                {"id": 7, "date": "2026-11-15T18:00:00.000Z", "status": "In Progress", "status_state": "in_progress",
                 "home_team_q1": 3, "home_team_q2": 7, "visitor_team_q1": 7, "visitor_team_q2": null,
                 "home_team_score": 10, "visitor_team_score": 7,
                 "home_team": {"abbreviation": "KC", "location": "Kansas City", "name": "Chiefs"},
                 "visitor_team": {"abbreviation": "BUF", "location": "Buffalo", "name": "Bills"}}
                """));
        assertThat(live.status()).isEqualTo(GameStatus.LIVE);
        assertThat(live.period()).isEqualTo(2);
        assertThat(live.clock()).isEqualTo("In Progress");
        assertThat(live.home().city()).isEqualTo("Kansas City");
        FeedGame scheduled = BallDontLieFeed.nfl(json("""
                {"id": 8, "date": "2026-11-16T01:20:00Z", "status": "Scheduled", "status_state": "scheduled",
                 "home_team": {"abbreviation": "DAL"}, "visitor_team": {"abbreviation": "PHI"}}
                """));
        assertThat(scheduled.status()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(scheduled.home().score()).isNull();
        assertThat(BallDontLieFeed.scoredPeriod(json("{\"home_team_ot\": 3, \"home_team_q4\": 7}"))).isEqualTo(5);
    }

    @Test
    void skipsGamesItCannotReadAndClassifiesConservatively() throws Exception {
        assertThat(BallDontLieFeed.nba(json("{\"id\": 1, \"status\": \"Final\"}"))).as("no start or teams").isNull();
        assertThat(BallDontLieFeed.classify("not started", 0)).isEqualTo(GameStatus.SCHEDULED);
        assertThat(BallDontLieFeed.classify("7:30 pm ET", 0)).isEqualTo(GameStatus.SCHEDULED);
        assertThat(BallDontLieFeed.classify("OT", 5)).isEqualTo(GameStatus.LIVE);
        assertThat(BallDontLieFeed.classify("Halftime", 2)).isEqualTo(GameStatus.LIVE);
        assertThat(BallDontLieFeed.classify("Postponed", 0)).isEqualTo(GameStatus.POSTPONED);
        assertThat(BallDontLieFeed.classify("Final/OT", 5)).isEqualTo(GameStatus.FINAL);
    }

    @Test
    void asksForEveryDateInOneRequestRecordsResponsesWithoutTheKeyAndReportsRateLimits() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/games", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            byte[] body = """
                    {"data": [{"id": 5, "datetime": "2026-11-13T00:30:00Z", "status": "Final",
                      "home_team_score": 1, "visitor_team_score": 2,
                      "home_team": {"abbreviation": "BOS"}, "visitor_team": {"abbreviation": "NYK"}}],
                     "meta": {"next_cursor": null}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/nfl/v1/games", exchange -> {
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path recordings = Files.createTempDirectory("feed-recordings");
            Clock clock = Clock.fixed(Instant.parse("2026-11-13T02:00:00Z"), ZoneOffset.UTC);
            BallDontLieFeed feed = new BallDontLieFeed("test-key", base, JSON,
                    new FeedRecorder(recordings.toString(), clock, JSON));
            List<LocalDate> dates = List.of(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12"),
                    LocalDate.parse("2026-11-13"));
            List<FeedGame> games = feed.games(League.NBA, dates);
            assertThat(games).extracting(FeedGame::id).containsExactly("NBA-5");
            assertThat(authorization.get()).isEqualTo("test-key");
            assertThat(java.net.URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                    .contains("dates[]=2026-11-11", "dates[]=2026-11-12", "dates[]=2026-11-13");
            Path recorded = recordings.resolve("nba/2026-11-12/2026-11-13T02-00-00Z_games.json");
            assertThat(Files.readString(recorded)).contains("\"league\" : \"NBA\"", "\"endpoint\" : \"games\"",
                    "\"status\" : 200", "\"id\" : 5").doesNotContain("test-key");
            assertThatThrownBy(() -> feed.games(League.NFL, dates))
                    .isInstanceOfSatisfying(GameFeed.FeedUnavailable.class,
                            exception -> assertThat(exception.rateLimited()).isTrue());
            assertThatThrownBy(() -> new BallDontLieFeed("", base, JSON, new FeedRecorder("", clock, JSON))
                    .games(League.NBA, dates))
                    .isInstanceOf(GameFeed.FeedUnavailable.class).hasMessageContaining("No BallDontLie API key");
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode json(String value) throws Exception {
        return JSON.readTree(value);
    }
}
