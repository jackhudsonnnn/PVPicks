package com.p2picks.app.sports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Polls each league once per interval with a single request covering yesterday, today and tomorrow in New York.
 * That catches late games however the provider dates them (UTC or local) and keeps earlier games current while
 * they run past midnight. Games are placed by their own start time. A failed league backs off exponentially, at
 * least a minute when rate limited, capped at ten minutes; other leagues keep polling.
 */
@Component
class GamePoller {
    private static final Logger log = LoggerFactory.getLogger(GamePoller.class);
    private static final ZoneId NEW_YORK = ZoneId.of(GameSync.NEW_YORK);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final GameFeed feed;
    private final GameSync sync;
    private final Clock clock;
    private final Duration interval;
    private final Map<League, Backoff> backoff = new EnumMap<>(League.class);

    GamePoller(GameFeed feed, GameSync sync, Clock clock,
            @Value("${pvpicks.sports.poll-interval:PT60S}") Duration interval) {
        this.feed = feed;
        this.sync = sync;
        this.clock = clock;
        this.interval = interval;
    }

    void poll() {
        for (League league : League.values()) pollLeague(league);
    }

    private void pollLeague(League league) {
        Instant now = clock.instant();
        Backoff state = backoff.get(league);
        if (state != null && now.isBefore(state.retryAt())) return;
        LocalDate today = LocalDate.ofInstant(now, NEW_YORK);
        try {
            List<FeedGame> games = feed.games(league, List.of(today.minusDays(1), today, today.plusDays(1)));
            sync.record(league, dedupe(games), now);
            if (state != null) log.info("{} feed recovered", league);
            backoff.remove(league);
        } catch (GameFeed.FeedUnavailable exception) {
            int failures = state == null ? 1 : state.failures() + 1;
            Duration delay = interval.multipliedBy(1L << Math.min(failures, 6));
            if (exception.rateLimited() && delay.compareTo(Duration.ofMinutes(1)) < 0) delay = Duration.ofMinutes(1);
            if (delay.compareTo(MAX_BACKOFF) > 0) delay = MAX_BACKOFF;
            backoff.put(league, new Backoff(failures, now.plus(delay)));
            sync.failure(league, exception.getMessage(), now);
            if (failures == 1) log.warn("{} feed unavailable: {}", league, exception.getMessage());
        }
    }

    /** A game listed under two of the requested dates appears once, as its last listing. */
    private static List<FeedGame> dedupe(List<FeedGame> games) {
        Map<String, FeedGame> byId = new java.util.LinkedHashMap<>();
        for (FeedGame game : games) byId.put(game.id(), game);
        return new ArrayList<>(byId.values());
    }

    private record Backoff(int failures, Instant retryAt) {}
}
