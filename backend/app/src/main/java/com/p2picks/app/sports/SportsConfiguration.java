package com.p2picks.app.sports;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class SportsConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Polls BallDontLie only when an API key is configured; without one the ticker reports unavailable. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("!'${pvpicks.sports.balldontlie.api-key:}'.isBlank()")
    @EnableScheduling
    static class PollScheduling {
        private static final Logger log = LoggerFactory.getLogger(PollScheduling.class);
        private final GamePoller poller;

        PollScheduling(GamePoller poller) {
            this.poller = poller;
        }

        @Scheduled(fixedDelayString = "${pvpicks.sports.poll-interval:PT60S}")
        void poll() {
            try {
                poller.poll();
            } catch (RuntimeException exception) {
                log.warn("Sports feed poll failed", exception);
            }
        }
    }
}
