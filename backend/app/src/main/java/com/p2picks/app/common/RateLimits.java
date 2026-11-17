package com.p2picks.app.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Per-account limits on the endpoints people could spam (Kafka & Redis, item 4): chat, reactions, polls, proposals,
 * picks, search, follows, blocks, feedback, creating tables, adding members and member search. Signed-out requests count by address. Over the limit, the request
 * gets 429 {@code rate_limited} with Retry-After. {@code pvpicks.rate-limits.enabled=false} turns them off (the
 * shared integration-test context does, and tests the limits directly). Sign-in has its own throttle.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimits implements WebMvcConfigurer {
    /** One limited operation: the methods and path pattern it covers, and its budget per window. */
    public record Rule(String bucket, Set<String> methods, String pattern, int limit, Duration window) {}

    static final Duration MINUTE = Duration.ofMinutes(1);
    public static final List<Rule> RULES = List.of(
            new Rule("chat.send", Set.of("POST"), "/tables/*/messages", 30, MINUTE),
            new Rule("chat.poll", Set.of("POST"), "/tables/*/polls", 5, MINUTE),
            new Rule("chat.vote", Set.of("PUT", "DELETE"), "/messages/*/vote", 30, MINUTE),
            new Rule("chat.reaction", Set.of("PUT", "DELETE"), "/messages/*/reaction", 60, MINUTE),
            new Rule("bets.propose", Set.of("POST"), "/tables/*/bets", 10, MINUTE),
            new Rule("bets.pick", Set.of("PUT", "DELETE"), "/bets/*/pick", 30, MINUTE),
            new Rule("users.search", Set.of("GET"), "/users", 30, MINUTE),
            new Rule("social.follow", Set.of("POST", "DELETE"), "/follows/*", 30, MINUTE),
            new Rule("social.block", Set.of("POST", "DELETE"), "/blocks/*", 30, MINUTE),
            new Rule("feedback", Set.of("POST"), "/feedback", 5, Duration.ofHours(1)),
            // Growing tables and sending requests (membership limits cap the totals; these cap the pace).
            new Rule("tables.create", Set.of("POST"), "/tables", 10, Duration.ofHours(1)),
            new Rule("tables.add", Set.of("POST"), "/tables/*/members", 30, Duration.ofHours(1)),
            new Rule("tables.member_search", Set.of("GET"), "/tables/*/members", 60, MINUTE));

    private final RateLimiter limiter;
    private final boolean enabled;

    RateLimits(RateLimiter limiter, @Value("${pvpicks.rate-limits.enabled:true}") boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (enabled) registry.addInterceptor(new Interceptor(limiter, RULES));
    }

    /** Checks each request against the first matching rule. */
    public static final class Interceptor implements HandlerInterceptor {
        private static final AntPathMatcher PATHS = new AntPathMatcher();
        private final RateLimiter limiter;
        private final List<Rule> rules;

        public Interceptor(RateLimiter limiter, List<Rule> rules) {
            this.limiter = limiter;
            this.rules = rules;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            for (Rule rule : rules) {
                if (!rule.methods().contains(request.getMethod()) || !PATHS.match(rule.pattern(), path)) continue;
                RateLimiter.Decision decision = limiter.acquire(rule.bucket(), subject(request), rule.limit(),
                        rule.window());
                if (decision.allowed()) return true;
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"rate_limited\",\"message\":\"You're doing that too often. "
                        + "Wait a moment and try again.\"}");
                return false;
            }
            return true;
        }

        private static String subject(HttpServletRequest request) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt) return "user:" + jwt.getToken().getSubject();
            return "address:" + request.getRemoteAddr();
        }
    }
}
