package com.p2picks.app.sports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SportsIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private GameSync sync;

    /** The scripted feed (ADR 0004): only automated tests use it. */
    private static final class ScriptedFeed implements GameFeed {
        final Map<League, List<FeedGame>> games = new EnumMap<>(League.class);
        boolean down;

        @Override
        public List<FeedGame> gamesOn(League league, LocalDate date) throws FeedUnavailable {
            if (down) throw new FeedUnavailable("scripted outage", false, null);
            return games.getOrDefault(league, List.of());
        }
    }

    @Test
    void pollingStoresSnapshotsAndRecordsOneEventPerChangingPoll() {
        String suffix = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40));
        Instant now = Instant.parse("2026-11-13T01:00:00Z");
        ScriptedFeed feed = new ScriptedFeed();
        FeedGame game = game(League.NBA, "p" + suffix, GameStatus.LIVE, now.minusSeconds(3600), 3, "5:32", 71, 68);
        feed.games.put(League.NBA, List.of(game));
        GamePoller poller = new GamePoller(feed, sync, Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(60));
        int before = events();

        poller.poll();
        assertThat(jdbc.queryForObject("SELECT home_score FROM games WHERE id = ?", Integer.class, game.id()))
                .isEqualTo(71);
        assertThat(events()).isEqualTo(before + 1);
        poller.poll();
        assertThat(events()).as("unchanged poll").isEqualTo(before + 1);

        feed.games.put(League.NBA, List.of(game(League.NBA, "p" + suffix, GameStatus.FINAL, now.minusSeconds(3600),
                null, null, 99, 98)));
        new GamePoller(feed, sync, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC), Duration.ofSeconds(60)).poll();
        assertThat(jdbc.queryForObject("SELECT status FROM games WHERE id = ?", String.class, game.id()))
                .isEqualTo("FINAL");
        assertThat(events()).isEqualTo(before + 2);

        feed.down = true;
        poller.poll();
        assertThat(jdbc.queryForObject("SELECT last_failure FROM sports_feed_status WHERE league = 'NBA'",
                String.class)).isEqualTo("scripted outage");
    }

    @Test
    void tickerShowsTodaysNewYorkGamesAndEarlierLiveGamesAcrossDaylightSaving() {
        // 23:30 EDT on Sunday 8 March 2026, the day clocks moved forward.
        Instant now = Instant.parse("2026-03-09T03:30:00Z");
        String suffix = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40));
        FeedGame today = game(League.NBA, "today" + suffix, GameStatus.SCHEDULED,
                Instant.parse("2026-03-08T23:00:00Z"), null, null, null, null);
        FeedGame tomorrow = game(League.NBA, "tomorrow" + suffix, GameStatus.SCHEDULED,
                Instant.parse("2026-03-09T04:30:00Z"), null, null, null, null);
        FeedGame lateLive = game(League.NFL, "late" + suffix, GameStatus.LIVE,
                Instant.parse("2026-03-08T03:00:00Z"), 4, "1:02", 20, 17);
        FeedGame oldFinal = game(League.NFL, "old" + suffix, GameStatus.FINAL,
                Instant.parse("2026-03-07T20:00:00Z"), null, null, 3, 0);
        sync.record(League.NBA, List.of(today, tomorrow), now);
        sync.record(League.NFL, List.of(lateLive, oldFinal), now);

        TickerService ticker = new TickerService(jdbc, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(3));
        List<String> mine = ticker.ticker().orElseThrow().stream().map(TickerService.TickerGame::id)
                .filter(id -> id.endsWith(suffix)).toList();
        assertThat(mine).containsExactly(lateLive.id(), today.id());

        TickerService stale = new TickerService(jdbc, Clock.fixed(now.plus(Duration.ofMinutes(4)), ZoneOffset.UTC),
                Duration.ofMinutes(3));
        assertThat(stale.ticker()).as("an outage is not an empty ticker").isEmpty();
    }

    @Test
    void tickerEndpointMatchesTheContractAndReportsAnUnavailableFeed() throws Exception {
        UUID user = UUID.randomUUID();
        String name = "tk" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 1, ?, 'PLAYER', 'ACTIVE')",
                user, name, passwords.encode("a secure test password"));
        Cookie session = loginAs(name);

        jdbc.update("UPDATE sports_feed_status SET last_success_at = now() - interval '1 hour'");
        JsonNode unavailable = json.readTree(mvc.perform(get("/ticker").cookie(session))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString());
        assertThat(unavailable.path("code").asText()).isEqualTo("feed_unavailable");

        Instant now = Instant.now();
        List<FeedGame> games = new ArrayList<>();
        games.add(game(League.NBA, "now" + name, GameStatus.LIVE, now.minusSeconds(1800), 2, "8:00", 40, 38));
        sync.record(League.NBA, games, now);
        sync.record(League.NFL, List.of(), now);
        JsonNode snapshot = json.readTree(mvc.perform(get("/ticker").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("TickerSnapshot").validate(snapshot)).isEmpty();
        assertThat(snapshot.path("games").toString()).contains("NBA-now" + name);
        mvc.perform(get("/ticker")).andExpect(status().isUnauthorized());
    }

    private int events() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'sports.games.changed.v1'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private static FeedGame game(League league, String id, GameStatus status, Instant startsAt, Integer period,
            String clock, Integer home, Integer away) {
        return new FeedGame(league, id, status, startsAt, period, clock,
                new FeedGame.Team("HOM", "Home", "Home City", home),
                new FeedGame.Team("AWY", "Away", "Away City", away));
    }
}
