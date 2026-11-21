package com.p2picks.app.config;

import com.p2picks.app.common.AppMode;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The {@link AppMode} bean, and Flyway, which migrates only once the mode checks out. */
@Configuration(proxyBeanMethods = false)
class ModeConfiguration {

    @Bean
    AppMode appMode(Environment environment) {
        return AppMode.of(environment);
    }

    @Bean
    FlywayMigrationStrategy checkedMigrationStrategy(Environment environment) {
        return flyway -> {
            AppMode.of(environment);
            flyway.migrate();
        };
    }
}
