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
    private final U2PickExpiry u2pickExpiry;

    BetLockScheduling(BetLockWorker worker, SportsResolutionWorker sports, U2PickExpiry u2pickExpiry) {
        this.worker = worker;
        this.sports = sports;
        this.u2pickExpiry = u2pickExpiry;
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

    /** Uncalled U2Pick bets wash after seven days; checking every ten minutes is plenty. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    void expireU2Picks() {
        u2pickExpiry.poll();
    }
}
