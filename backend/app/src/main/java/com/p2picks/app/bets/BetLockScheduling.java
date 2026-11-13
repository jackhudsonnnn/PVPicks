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

    BetLockScheduling(BetLockWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 5000)
    void poll() {
        worker.poll();
    }
}
