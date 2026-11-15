package com.p2picks.app.sports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps player stats and the active-player list current (ADR 0005), one provider request per run so the run interval
 * is the request budget. Live games come first, each turn going to the game that has waited longest; finished games
 * (within six hours of their start) refresh every 15 minutes so late corrections land. With nothing to do for games,
 * the turn refreshes the next page of a league's active players, at most once a day. A rate-limited request pauses
 * everything for a minute. Responses are also recorded when feed recording is on.
 */
@Component
class StatsPoller {
    private static final Logger log = LoggerFactory.getLogger(StatsPoller.class);
    static final int BATCH = 2;
    static final Duration FINAL_REFRESH = Duration.ofMinutes(15);
    static final Duration FINAL_WINDOW = Duration.ofHours(6);
    /** While games are live and a refresh is due, every this-many-th request goes to it, so it can't starve. */
    static final int PLAYER_SHARE = 4;
    private static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final BallDontLieFeed feed;
    private final StatsSync sync;
    private final Clock clock;
    /** How often each league's active-player list is refreshed (every page, so every response is recorded). */
    private final Duration playersInterval;
    private final Map<String, Instant> lastFetched = new HashMap<>();
    /** A league's active-list refresh in progress: where it started and the next page. */
    private final Map<League, Refresh> refreshing = new EnumMap<>(League.class);
    private Instant pausedUntil = Instant.MIN;
    private long runs;

    StatsPoller(JdbcTemplate jdbc, BallDontLieFeed feed, StatsSync sync, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${pvpicks.sports.players.interval:PT10M}")
            Duration playersInterval) {
        this.jdbc = jdbc;
        this.feed = feed;
        this.sync = sync;
        this.clock = clock;
        this.playersInterval = playersInterval;
    }

    record Candidate(String id, League league, String providerId, boolean live) {}

    private record Refresh(Instant startedAt, String cursor) {}

    void runOnce() {
        if (!feed.configured()) return;
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) return;
        try {
            List<Candidate> games = batch(candidates(now), lastFetched, now);
            boolean playersTurn = ++runs % PLAYER_SHARE == 0 && playersDue(now);
            if (!games.isEmpty() && !playersTurn) {
                fetchGames(games, now);
            } else {
                refreshPlayers(now);
            }
        } catch (GameFeed.FeedUnavailable exception) {
            if (exception.rateLimited()) pausedUntil = now.plus(RATE_LIMIT_PAUSE);
            log.debug("Player data unavailable: {}", exception.getMessage());
        }
        lastFetched.values().removeIf(at -> at.isBefore(now.minus(FINAL_WINDOW.multipliedBy(2))));
    }

    private void fetchGames(List<Candidate> games, Instant now) throws GameFeed.FeedUnavailable {
        games.forEach(game -> lastFetched.put(game.id(), now));
        Map<String, List<StatLine>> lines = feed.playerStats(games.getFirst().league(),
                games.stream().map(Candidate::providerId).toList());
        for (Candidate game : games) sync.recordGame(game.id(), lines.get(game.providerId()), now);
    }

    private boolean playersDue(Instant now) {
        if (!refreshing.isEmpty()) return true;
        for (League league : League.values()) {
            if (refreshDue(sync.playersRefreshedAt(league), now, playersInterval)) return true;
        }
        return false;
    }

    /** Due when the last full refresh started at least {@code interval} ago (or never ran). */
    static boolean refreshDue(Instant last, Instant now, Duration interval) {
        return last == null || !last.plus(interval).isAfter(now);
    }

    private void refreshPlayers(Instant now) throws GameFeed.FeedUnavailable {
        for (League league : League.values()) {
            Refresh refresh = refreshing.get(league);
            if (refresh == null) {
                if (!refreshDue(sync.playersRefreshedAt(league), now, playersInterval)) continue;
                refresh = new Refresh(now, null);
            }
            BallDontLieFeed.PlayerPage page = feed.activePlayers(league, refresh.cursor());
            boolean finished = page.nextCursor() == null;
            sync.recordActivePlayers(league, page.players(), refresh.startedAt(), now, finished);
            if (finished) refreshing.remove(league);
            else refreshing.put(league, new Refresh(refresh.startedAt(), page.nextCursor()));
            return;
        }
    }

    private List<Candidate> candidates(Instant now) {
        return jdbc.query("""
                SELECT id, league, provider_id, status FROM games
                WHERE provider_id NOT LIKE 'DEV-%' AND (status = 'LIVE' OR (status = 'FINAL' AND starts_at > ?))
                """, (rs, row) -> new Candidate(rs.getString("id"), League.valueOf(rs.getString("league")),
                rs.getString("provider_id"), rs.getString("status").equals("LIVE")),
                java.sql.Timestamp.from(now.minus(FINAL_WINDOW)));
    }

    /**
     * The game that has waited longest, plus the next-longest-waiting due games in its league, up to {@link #BATCH}:
     * one request covers them all. Two games fit one 100-row page in both leagues.
     */
    static List<Candidate> batch(List<Candidate> games, Map<String, Instant> lastFetched, Instant now) {
        Optional<Candidate> first = next(games, lastFetched, now);
        if (first.isEmpty()) return List.of();
        List<Candidate> batch = new ArrayList<>(List.of(first.get()));
        List<Candidate> rest = new ArrayList<>(games.stream()
                .filter(g -> g.league() == first.get().league() && !g.equals(first.get())).toList());
        while (batch.size() < BATCH) {
            Optional<Candidate> another = next(rest, lastFetched, now);
            if (another.isEmpty()) break;
            batch.add(another.get());
            rest.remove(another.get());
        }
        return batch;
    }

    /** The game that has waited longest; finished games only once their refresh interval has passed. */
    static Optional<Candidate> next(List<Candidate> games, Map<String, Instant> lastFetched, Instant now) {
        return games.stream()
                .filter(g -> g.live() || lastFetched.getOrDefault(g.id(), Instant.MIN)
                        .isBefore(now.minus(FINAL_REFRESH)))
                .min(Comparator.comparing((Candidate g) -> lastFetched.getOrDefault(g.id(), Instant.MIN))
                        .thenComparing(g -> !g.live())
                        .thenComparing(Candidate::id));
    }
}
