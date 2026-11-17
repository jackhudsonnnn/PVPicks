package com.p2picks.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The lan profile's transport rules: an HTTPS origin and Secure cookies; dev keeps HTTP loopback. */
class LanSecurityTest {
    @Test
    void lanRequiresTheHttpsOriginPlayersUse() {
        new DevOriginFilter("https://p2picks.example.com", true);
        assertThatThrownBy(() -> new DevOriginFilter("http://p2picks.example.com", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DevOriginFilter("https://p2picks.example.com/path", true))
                .isInstanceOf(IllegalArgumentException.class);
        new DevOriginFilter("http://127.0.0.1:5173", false);
        assertThatThrownBy(() -> new DevOriginFilter("https://p2picks.example.com", false))
                .as("dev stays on loopback").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cookiesAreSecureOnlyWhenConfigured() {
        assertThat(new SessionCookies(true).session("token", Duration.ofHours(8)))
                .contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(new SessionCookies(false).session("token", Duration.ofHours(8))).doesNotContain("Secure");
        assertThat(new SessionCookies(true).cleared()).contains("Max-Age=0", "Secure");
    }
}
