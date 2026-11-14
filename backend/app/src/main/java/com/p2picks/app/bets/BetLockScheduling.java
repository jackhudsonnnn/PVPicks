package com.p2picks.app.bets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pvpicks.bet-lock-worker.enabled", havingValue = "true")
@EnableScheduling
class BetLockScheduling {
    private final BetLockWorker worker;
    private final SportsResolutionWorker sports;

    BetLockScheduling(BetLockWorker worker, SportsResolutionWorker sports) {
        this.worker = worker;
        this.sports = sports;
    }

    @Scheduled(fixedDelay = 5000)
    void poll() {
        worker.poll();
    }

    /** Sports results only change when the feed does (about once a minute), so check less often. */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    void resolveSports() {
        sports.poll();
    }
}
