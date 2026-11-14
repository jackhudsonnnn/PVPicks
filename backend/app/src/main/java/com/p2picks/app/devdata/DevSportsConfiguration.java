package com.p2picks.app.devdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.StatsSync;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Only with `pvpicks.dev-sports.dir` set (the dev profile sets it from PVPICKS_DEV_SPORTS_DIR). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${pvpicks.dev-sports.dir:}'.isBlank()")
@EnableScheduling
class DevSportsConfiguration {
    @Bean
    DevSportsRunner devSportsRunner(@Value("${pvpicks.dev-sports.dir}") String dir, ObjectMapper json, GameSync games,
            StatsSync stats, Clock clock) {
        return new DevSportsRunner(dir, json, games, stats, clock);
    }

    @Bean
    Ticker devSportsTicker(DevSportsRunner runner) {
        return new Ticker(runner);
    }

    static class Ticker {
        private final DevSportsRunner runner;

        Ticker(DevSportsRunner runner) {
            this.runner = runner;
        }

        @Scheduled(fixedDelayString = "${pvpicks.dev-sports.tick:PT2S}")
        void tick() {
            runner.tick();
        }
    }
}
