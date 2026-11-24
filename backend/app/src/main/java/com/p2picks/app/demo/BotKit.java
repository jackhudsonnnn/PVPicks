package com.p2picks.app.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import com.p2picks.app.security.PasswordlessSignIn;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The bots' scheduler, shared by every planner: actions planned for later and run when due, each bot's live session,
 * the decisions already made (one per thing), what's been done (for the Dev page), and the random source tests fix.
 */
final class BotKit {
    private static final Logger log = LoggerFactory.getLogger(BotKit.class);

    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final Duration tick;
    final Map<UUID, DemoUser> bots;
    final Set<String> decided = new HashSet<>();
    private final Clock clock;
    private final PasswordlessSignIn signIn;
    private final Map<UUID, Jwt> sessions = new HashMap<>();
    private final PriorityQueue<Planned> queue = new PriorityQueue<>((a, b) -> a.at().compareTo(b.at()));
    private final Map<String, Integer> done = new java.util.TreeMap<>();
    private Random random = new Random();
    /** When the bots started (or were last reset): follow-backs only answer follows made since. */
    private Instant since;

    private record Planned(Instant at, String what, Runnable action) {}

    BotKit(JdbcTemplate jdbc, ObjectMapper json, Clock clock, Duration tick, PasswordlessSignIn signIn,
            Map<UUID, DemoUser> bots) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.tick = tick;
        this.signIn = signIn;
        this.bots = bots;
        this.since = clock.instant();
    }

    Instant since() {
        return since;
    }

    Random random() {
        return random;
    }

    void reseed(long seed) {
        random = new Random(seed);
    }

    /** Drops sessions, plans and decisions; after a reset none of them mean anything. */
    void forget() {
        sessions.clear();
        decided.clear();
        queue.clear();
        done.clear();
        since = clock.instant();
    }

    int queued() {
        return queue.size();
    }

    /** Actions of this kind waiting to run. */
    int queued(String what) {
        return (int) queue.stream().filter(p -> p.what().equals(what)).count();
    }

    Map<String, Integer> done() {
        return Map.copyOf(done);
    }

    /** Whether this is the first time {@code bot} decides about {@code thing}; records it. */
    boolean decide(String what, UUID thing, UUID bot) {
        return decided.add(what + ":" + thing + ":" + bot);
    }

    void plan(Instant at, String what, Supplier<String> action) {
        queue.add(new Planned(at, what, () -> {
            String outcome = action.get();
            done.merge(what + (outcome == null ? "" : " " + outcome.toLowerCase()), 1, Integer::sum);
        }));
    }

    void runDue(Instant now) {
        int ran = 0;
        // A cap per tick keeps one tick short even after a pause; the rest run next time.
        while (!queue.isEmpty() && !queue.peek().at().isAfter(now) && ran++ < 50) {
            Planned planned = queue.poll();
            try {
                planned.action().run();
            } catch (RuntimeException exception) {
                done.merge(planned.what() + " failed", 1, Integer::sum);
                log.debug("Demo bot {} failed: {}", planned.what(), exception.toString());
            }
        }
    }

    /** Runs an action as the bot with a live session; an UNAUTHORIZED outcome drops the session for next time. */
    String act(UUID bot, Function<Jwt, String> action) {
        Jwt jwt = sessions.get(bot);
        if (jwt == null || jwt.getExpiresAt() == null
                || jwt.getExpiresAt().isBefore(clock.instant().plus(Duration.ofMinutes(10)))) {
            jwt = signIn.session(bot);
            if (jwt == null) return "NO_SESSION";
            sessions.put(bot, jwt);
        }
        String outcome = action.apply(jwt);
        if ("UNAUTHORIZED".equals(outcome)) sessions.remove(bot);
        return outcome;
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String array(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    List<String> strings(String jsonArray) {
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unreadable choices", exception);
        }
    }
}
