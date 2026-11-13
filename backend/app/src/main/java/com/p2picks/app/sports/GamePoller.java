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
 * Polls each league for today's New York games, plus yesterday's while any are still unfinished (late games run
 * past midnight). A failed league backs off exponentially, at least a minute when rate limited, capped at ten
 * minutes; other leagues keep polling.
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
            List<FeedGame> games = new ArrayList<>(feed.gamesOn(league, today));
            if (sync.hasUnfinishedGames(league, today.minusDays(1))) games.addAll(feed.gamesOn(league, today.minusDays(1)));
            sync.record(league, games, now);
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

    private record Backoff(int failures, Instant retryAt) {}
}
