package com.p2picks.app.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class DevelopmentOnlyProfile {

    DevelopmentOnlyProfile(Environment environment) {
        requireDevProfile(environment);
    }

    static void requireDevProfile(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !"dev".equals(profiles[0])) {
            throw new IllegalStateException(
                    "Only the dev profile is available; LAN and production are not configured");
        }
    }
}
