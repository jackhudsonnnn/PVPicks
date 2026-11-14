package com.p2picks.app.sports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsPollerTest {
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");
    private static final StatsPoller.Candidate LIVE_A = new StatsPoller.Candidate("NBA-1", League.NBA, "1", true);
    private static final StatsPoller.Candidate LIVE_B = new StatsPoller.Candidate("NBA-2", League.NBA, "2", true);
    private static final StatsPoller.Candidate DONE = new StatsPoller.Candidate("NBA-3", League.NBA, "3", false);

    @Test
    void neverFetchedGamesGoFirstLiveBeforeFinished() {
        assertThat(StatsPoller.next(List.of(DONE, LIVE_B, LIVE_A), Map.of(), NOW)).contains(LIVE_A);
    }

    @Test
    void liveGamesTakeTurnsByWhoWaitedLongest() {
        Map<String, Instant> fetched = new HashMap<>(Map.of("NBA-1", NOW.minusSeconds(20), "NBA-2", NOW.minusSeconds(40),
                "NBA-3", NOW.minusSeconds(60)));
        assertThat(StatsPoller.next(List.of(LIVE_A, LIVE_B, DONE), fetched, NOW)).contains(LIVE_B);
    }

    @Test
    void finishedGamesRefreshOnlyEveryFifteenMinutes() {
        Map<String, Instant> recent = Map.of("NBA-3", NOW.minus(StatsPoller.FINAL_REFRESH).plusSeconds(1));
        assertThat(StatsPoller.next(List.of(DONE), recent, NOW)).isEmpty();
        Map<String, Instant> stale = Map.of("NBA-3", NOW.minus(StatsPoller.FINAL_REFRESH).minusSeconds(1));
        assertThat(StatsPoller.next(List.of(DONE), stale, NOW)).contains(DONE);
    }
}
