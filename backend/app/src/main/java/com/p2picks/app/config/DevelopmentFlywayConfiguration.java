package com.p2picks.app;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class DevelopmentFlywayConfiguration {

    @Bean
    FlywayMigrationStrategy developmentMigrationStrategy(Environment environment) {
        return flyway -> {
            DevelopmentOnlyProfile.requireDevProfile(environment);
            flyway.migrate();
        };
    }
}
