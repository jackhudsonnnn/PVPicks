package com.p2picks.app.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * Per-account limits on the endpoints people could spam (Kafka & Redis, item 4): chat, reactions, polls,
 * proposals, picks, search, follows, blocks, feedback, reports, creating tables, adding members and member search.
 * On top of those, every write (POST, PUT, PATCH, DELETE) counts toward one {@link #WRITES_PER_MINUTE} budget per
 * account ({@link #BACKSTOP}). Write endpoints without a rule of their own are listed in {@link #EXEMPT} with the
 * reason; a test fails when a new one is neither. Signed-out requests count by address. Over a limit, the request
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
            new Rule("chat.gif-favorite", Set.of("PUT", "DELETE"), "/me/gif-favorites/*", 60, MINUTE),
            new Rule("bets.propose", Set.of("POST"), "/tables/*/bets", 10, MINUTE),
            new Rule("bets.pick", Set.of("PUT", "DELETE"), "/bets/*/pick", 30, MINUTE),
            new Rule("bets.jackblack.seat", Set.of("PUT", "DELETE"), "/bets/*/jackblack/seat", 30, MINUTE),
            // A hand can take many hits and splits; a decision timer runs out first.
            new Rule("bets.jackblack.act", Set.of("POST"), "/bets/*/jackblack/actions", 60, MINUTE),
            new Rule("bets.holdem.seat", Set.of("PUT", "DELETE"), "/bets/*/holdem/seat", 30, MINUTE),
            new Rule("bets.rematch", Set.of("POST"), "/bets/*/rematch", 10, MINUTE),
            new Rule("bets.holdem.act", Set.of("POST"), "/bets/*/holdem/actions", 120, MINUTE),
            new Rule("users.search", Set.of("GET"), "/users", 30, MINUTE),
            new Rule("social.follow", Set.of("POST", "DELETE"), "/follows/*", 30, MINUTE),
            new Rule("social.block", Set.of("POST", "DELETE"), "/blocks/*", 30, MINUTE),
            new Rule("feedback", Set.of("POST"), "/feedback", 5, Duration.ofHours(1)),
            // Also one open report per person and 20 new a day (ReportService).
            new Rule("reports", Set.of("POST"), "/reports", 10, Duration.ofHours(1)),
            // Growing tables and sending requests (membership limits cap the totals; these cap the pace).
            new Rule("tables.create", Set.of("POST"), "/tables", 10, Duration.ofHours(1)),
            new Rule("tables.add", Set.of("POST"), "/tables/*/members", 30, Duration.ofHours(1)),
            new Rule("tables.member_search", Set.of("GET"), "/tables/*/members", 60, MINUTE),
            // Browsers send timings about once a minute and when the tab hides.
            new Rule("perf", Set.of("POST"), "/perf", 10, MINUTE));

    public static final int WRITES_PER_MINUTE = 120;
    static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    /** Every write, whatever its own rule: a ceiling no honest client reaches. */
    public static final Rule BACKSTOP = new Rule("writes", WRITES, "/**", WRITES_PER_MINUTE, MINUTE);

    /**
     * Write endpoints ("METHOD pattern", {@code *} for a path variable) without a rule of their own, and why the
     * backstop is enough. {@code RateLimitCoverageTest} fails on a write endpoint that has neither.
     */
    public static final Map<String, String> EXEMPT = Map.ofEntries(
            Map.entry("POST /auth/login", "Sign-in throttle (failures per account name and address)."),
            Map.entry("POST /auth/code", "Sign-in throttle (wrong codes per player, plus a per-address backstop)."),
            Map.entry("POST /auth/password", "Sign-in throttle (failures per account and address)."),
            Map.entry("POST /auth/logout", "Ends your own session."),
            Map.entry("POST /me/delete", "Your own account, once; confirmed by typing your username."),
            Map.entry("PATCH /me/profile", "Your own profile; usernames are unique, nothing fans out."),
            Map.entry("PUT /me/debt-cap", "Your own setting."),
            Map.entry("PUT /me/invite-preferences", "Your own setting."),
            Map.entry("POST /invites/*/respond", "Once per request; requests are capped at 100 waiting."),
            Map.entry("PUT /tables/*/last-read", "Your own read marker; idempotent."),
            Map.entry("POST /tables/*/tomatoes", "A 5-second cooldown per thrower and table (TomatoService)."),
            Map.entry("PATCH /tables/*", "Hosts only."),
            Map.entry("DELETE /tables/*", "Hosts only, once."),
            Map.entry("PATCH /tables/*/settings", "Hosts only."),
            Map.entry("PUT /tables/*/hosts/*", "Hosts only."),
            Map.entry("DELETE /tables/*/hosts/*", "Hosts only."),
            Map.entry("PUT /tables/*/members/*/permissions", "Hosts only."),
            Map.entry("DELETE /tables/*/members/*", "Hosts, or leaving yourself; once per member."),
            Map.entry("PUT /tables/*/quick-picks", "Hosts only."),
            Map.entry("POST /tables/*/quick-picks/share", "Hosts only."),
            Map.entry("DELETE /tables/*/quick-picks/share", "Hosts only."),
            Map.entry("POST /tables/*/quick-picks/rotate", "Hosts only."),
            Map.entry("POST /tables/*/settle-points", "Hosts only; once per open balance."),
            Map.entry("POST /bets/*/settle", "Only the bet's settler (its settler rule), once per bet."),
            Map.entry("POST /bets/*/override", "Hosts only, until points are settled."),
            Map.entry("POST /people/*/settles", "3 sends a day per pair (SettleRules)."),
            Map.entry("POST /settles/*/reject", "Once per send, within its 48-hour window."),
            Map.entry("POST /users/*/settlement-review/acknowledge", "Once per balance revision."),
            Map.entry("POST /admin/players", "Admins only."),
            Map.entry("POST /admin/players/*/code", "Admins only; rotating signs the player out."),
            Map.entry("DELETE /admin/players/*", "Admins only; washes the player's open bets."),
            Map.entry("POST /admin/reports/*/dismiss", "Admins only, once per report."),
            Map.entry("POST /admin/reports/*/strike", "Admins only, once per report."),
            Map.entry("POST /admin/users/*/unfreeze", "Admins only."),
            Map.entry("POST /admin/users/*/clear-strikes", "Admins only."),
            Map.entry("PUT /admin/feedback/*", "Admins only; idempotent."),
            Map.entry("DELETE /admin/sign-in-lockout", "Admins only; idempotent."),
            Map.entry("DELETE /admin/sign-in-lockout/players/*", "Admins only; idempotent."),
            Map.entry("POST /dev/demo/sign-in", "Dev profile only."),
            Map.entry("POST /dev/demo/reset", "Dev profile only."),
            Map.entry("POST /dev/sports/*/*/*", "Dev profile only."));

    private final RateLimiter limiter;
    private final boolean enabled;

    RateLimits(RateLimiter limiter, @Value("${pvpicks.rate-limits.enabled:true}") boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (enabled) registry.addInterceptor(new Interceptor(limiter, RULES, BACKSTOP));
    }

    /** Checks each request against the first matching rule, then against the backstop (when there is one). */
    public static final class Interceptor implements HandlerInterceptor {
        private static final AntPathMatcher PATHS = new AntPathMatcher();
        private final RateLimiter limiter;
        private final List<Rule> rules;
        private final Rule backstop;

        public Interceptor(RateLimiter limiter, List<Rule> rules) {
            this(limiter, rules, null);
        }

        public Interceptor(RateLimiter limiter, List<Rule> rules, Rule backstop) {
            this.limiter = limiter;
            this.rules = rules;
            this.backstop = backstop;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws IOException {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            for (Rule rule : rules) {
                if (!matches(rule, request.getMethod(), path)) continue;
                if (!allowed(rule, request, response)) return false;
                break;
            }
            return backstop == null || !matches(backstop, request.getMethod(), path)
                    || allowed(backstop, request, response);
        }

        static boolean matches(Rule rule, String method, String path) {
            return rule.methods().contains(method) && PATHS.match(rule.pattern(), path);
        }

        private boolean allowed(Rule rule, HttpServletRequest request, HttpServletResponse response)
                throws IOException {
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

        private static String subject(HttpServletRequest request) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt) return "user:" + jwt.getToken().getSubject();
            return "address:" + request.getRemoteAddr();
        }
    }
}
