package com.p2picks.app.config;

import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The application runs with exactly one supported profile: {@code dev} (local development) or {@code lan} (the LAN
 * watch party, docs: LAN watch party). Production is not configured, so anything else refuses to start.
 */
@Component
final class DevelopmentOnlyProfile {
    static final Set<String> SUPPORTED = Set.of("dev", "lan");

    DevelopmentOnlyProfile(Environment environment) {
        requireSupportedProfile(environment);
    }

    static void requireSupportedProfile(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !SUPPORTED.contains(profiles[0])) {
            throw new IllegalStateException(
                    "Only the dev and lan profiles are available; production is not configured");
        }
    }
}
