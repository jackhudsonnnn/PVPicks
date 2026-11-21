package com.p2picks.app.common;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * Which of the app's three settings this backend runs as, from its single Spring profile:
 * <ul>
 *   <li>{@link #DEV}: local development, with the demo world, the Dev page and the handbook;</li>
 *   <li>{@link #LAN}: the one-night LAN watch party (docs: LAN watch party), with the party table;</li>
 *   <li>{@link #PROD}: production, which isn't configured yet, so the backend refuses to start with it.</li>
 * </ul>
 * Code that behaves the same everywhere never asks. Mode-specific features are switched on by a property that only
 * that mode's {@code application-<mode>.yml} sets (for example {@code pvpicks.demo.enabled}, {@code
 * pvpicks.lan.party.enabled}), so integration tests can turn one on under the dev profile. Where behavior differs
 * inside shared code (security rules, the origin check), it asks for the {@code AppMode} bean.
 */
public enum AppMode {
    DEV, LAN, PROD;

    /** What the client sees at {@code GET /config}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode for exactly one supported profile; anything else stops the backend before it migrates or serves. */
    public static AppMode of(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 1) {
            if (profiles[0].equals("dev")) return DEV;
            if (profiles[0].equals("lan")) return LAN;
        }
        throw new IllegalStateException(
                "Run with exactly one of the dev and lan profiles; production is not configured yet");
    }
}
