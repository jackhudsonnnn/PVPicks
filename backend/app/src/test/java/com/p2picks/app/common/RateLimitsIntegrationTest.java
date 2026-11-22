package com.p2picks.app.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.IntegrationTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The Redis limiter against the test Redis, and the interceptor's matching, keys and 429 response. */
class RateLimitsIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private RateLimiter limiter;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void countsUsesPerSubjectWithinTheWindow() {
        String subject = "test:" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.acquire("unit", subject, 3, Duration.ofMinutes(1)).allowed()).isTrue();
        }
        RateLimiter.Decision blocked = limiter.acquire("unit", subject, 3, Duration.ofMinutes(1));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(limiter.acquire("unit", "test:" + UUID.randomUUID(), 3, Duration.ofMinutes(1)).allowed())
                .as("another subject has its own budget").isTrue();
        assertThat(limiter.acquire("other", subject, 3, Duration.ofMinutes(1)).allowed())
                .as("another bucket has its own budget").isTrue();
    }

    @Test
    void theInterceptorLimitsMatchingRequestsPerAccount() throws Exception {
        RateLimits.Interceptor interceptor = new RateLimits.Interceptor(limiter,
                List.of(new RateLimits.Rule("test.send." + UUID.randomUUID(), Set.of("POST"), "/tables/*/messages", 2,
                        Duration.ofMinutes(1))));
        String user = UUID.randomUUID().toString();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "HS256").subject(user).build()));
        String path = "/tables/" + UUID.randomUUID() + "/messages";
        assertThat(interceptor.preHandle(request("POST", path), new MockHttpServletResponse(), null)).isTrue();
        assertThat(interceptor.preHandle(request("GET", path), new MockHttpServletResponse(), null))
                .as("other methods aren't counted").isTrue();
        assertThat(interceptor.preHandle(request("POST", path), new MockHttpServletResponse(), null)).isTrue();
        MockHttpServletResponse limited = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/tables/" + UUID.randomUUID() + "/messages"), limited,
                null)).as("the budget is per account, across tables").isFalse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(json.readTree(limited.getContentAsString()).path("code").asText()).isEqualTo("rate_limited");
        assertThat(interceptor.preHandle(request("POST", "/feedback"), new MockHttpServletResponse(), null))
                .as("unmatched paths pass").isTrue();
    }

    @Test
    void theBackstopCountsEveryWriteOnTopOfItsOwnRule() throws Exception {
        String bucket = "test.writes." + UUID.randomUUID();
        RateLimits.Interceptor interceptor = new RateLimits.Interceptor(limiter,
                List.of(new RateLimits.Rule(bucket + ".chat", Set.of("POST"), "/tables/*/messages", 10,
                        Duration.ofMinutes(1))),
                new RateLimits.Rule(bucket, Set.of("POST", "PUT", "PATCH", "DELETE"), "/**", 3,
                        Duration.ofMinutes(1)));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "HS256").subject(UUID.randomUUID().toString()).build()));
        String table = "/tables/" + UUID.randomUUID();
        assertThat(interceptor.preHandle(request("POST", table + "/messages"), new MockHttpServletResponse(), null))
                .as("a ruled write counts toward both").isTrue();
        assertThat(interceptor.preHandle(request("PATCH", table), new MockHttpServletResponse(), null)).isTrue();
        assertThat(interceptor.preHandle(request("PUT", table + "/last-read"), new MockHttpServletResponse(), null))
                .isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.preHandle(request("GET", table), new MockHttpServletResponse(), null))
                    .as("reads aren't counted").isTrue();
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("DELETE", table), limited, null)).isFalse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(RateLimits.BACKSTOP.limit()).isEqualTo(RateLimits.WRITES_PER_MINUTE);
    }

    @Test
    void creatingTablesAndAddingMembersArePaced() throws Exception {
        RateLimits.Interceptor interceptor = new RateLimits.Interceptor(limiter, RateLimits.RULES);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "HS256").subject(UUID.randomUUID().toString()).build()));
        for (int i = 0; i < 10; i++) {
            assertThat(interceptor.preHandle(request("POST", "/tables"), new MockHttpServletResponse(), null)).isTrue();
        }
        assertThat(interceptor.preHandle(request("GET", "/tables"), new MockHttpServletResponse(), null))
                .as("reading your tables isn't limited").isTrue();
        MockHttpServletResponse created = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request("POST", "/tables"), created, null))
                .as("10 new tables an hour").isFalse();
        assertThat(created.getStatus()).isEqualTo(429);

        String members = "/tables/" + UUID.randomUUID() + "/members";
        for (int i = 0; i < 30; i++) {
            assertThat(interceptor.preHandle(request("POST", members), new MockHttpServletResponse(), null)).isTrue();
        }
        assertThat(interceptor.preHandle(request("POST", members), new MockHttpServletResponse(), null))
                .as("30 adds or requests an hour").isFalse();
        assertThat(interceptor.preHandle(request("GET", members), new MockHttpServletResponse(), null))
                .as("member search has its own budget").isTrue();
    }

    @Test
    void everyRuleCoversARealRoute() {
        assertThat(RateLimits.RULES).allSatisfy(rule -> assertThat(rule.limit()).isPositive());
        assertThat(RateLimits.RULES).extracting(RateLimits.Rule::bucket).doesNotHaveDuplicates();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("198.51.100.7");
        return request;
    }
}
