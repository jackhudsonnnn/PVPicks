package com.p2picks.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The lan profile's transport rules: an HTTPS origin and Secure cookies; dev keeps HTTP loopback. */
class LanSecurityTest {
    @Test
    void lanRequiresTheHttpsOriginPlayersUse() {
        new OriginFilter("https://p2picks.example.com", true);
        assertThatThrownBy(() -> new OriginFilter("http://p2picks.example.com", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OriginFilter("https://p2picks.example.com/path", true))
                .isInstanceOf(IllegalArgumentException.class);
        new OriginFilter("http://127.0.0.1:5173", false);
        assertThatThrownBy(() -> new OriginFilter("https://p2picks.example.com", false))
                .as("dev stays on loopback").isInstanceOf(IllegalArgumentException.class);
    }

    private static int post(OriginFilter filter, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/code");
        if (origin != null) request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void devAcceptsBothLoopbackNamesOnItsPortAndLanOnlyItsOrigin() throws Exception {
        OriginFilter dev = new OriginFilter("http://127.0.0.1:5173", false);
        assertThat(post(dev, "http://127.0.0.1:5173")).isEqualTo(200);
        assertThat(post(dev, "http://localhost:5173")).as("Vite prints this one").isEqualTo(200);
        assertThat(post(dev, "http://localhost:5174")).isEqualTo(403);
        assertThat(post(dev, "https://evil.example.com")).isEqualTo(403);
        assertThat(post(dev, null)).isEqualTo(403);
        OriginFilter lan = new OriginFilter("https://lan.p2picks.com", true);
        assertThat(post(lan, "https://lan.p2picks.com")).isEqualTo(200);
        assertThat(post(lan, "http://localhost:5173")).isEqualTo(403);
    }

    @Test
    void cookiesAreSecureOnlyWhenConfigured() {
        assertThat(new SessionCookies(true).session("token", Duration.ofHours(8)))
                .contains("Secure", "HttpOnly", "SameSite=Strict");
        assertThat(new SessionCookies(false).session("token", Duration.ofHours(8))).doesNotContain("Secure");
        assertThat(new SessionCookies(true).cleared()).contains("Max-Age=0", "Secure");
    }
}
