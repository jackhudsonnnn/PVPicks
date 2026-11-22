package com.p2picks.app.devdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.StatLine;
import com.p2picks.app.sports.StatsSync;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * What's playing: any number of scenarios and chunks at once, each feeding its own `DEV-` games. A tick writes each
 * running source's current state through the same game and stats sync the live feed uses, so bets on dev games lock,
 * resolve and settle exactly as on real ones. Run state is in memory: a backend restart stops everything (dev games
 * keep their last state).
 */
class DevSportsRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSportsRunner.class);
    static final List<Double> SPEEDS = List.of(0.5, 1.0, 2.0, 4.0, 8.0);

    enum Kind { SCENARIO, CHUNK }

    /** One running source. Chunks have a position that advances by speed; scenarios run on wall time. */
    static final class Run {
        final Kind kind;
        final String name;
        Instant startedAt;
        Duration position = Duration.ZERO;
        double speed = 1.0;
        boolean paused;
        Instant lastTick;
        String error;
        int games;
        int players;

        Run(Kind kind, String name, Instant now) {
            this.kind = kind;
            this.name = name;
            this.startedAt = now;
            this.lastTick = now;
        }
    }

    private final Path root;
    private final ObjectMapper json;
    private final GameSync games;
    private final StatsSync stats;
    private final Clock clock;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    DevSportsRunner(@Value("${pvpicks.dev-sports.dir}") String dir, ObjectMapper json, GameSync games,
            StatsSync stats, Clock clock) {
        this.root = Path.of(dir);
        this.json = json;
        this.games = games;
        this.stats = stats;
        this.clock = clock;
    }

    Path root() {
        return root;
    }

    ObjectMapper json() {
        return json;
    }

    static String key(Kind kind, String name) {
        return kind + ":" + name;
    }

    Run run(Kind kind, String name) {
        return runs.get(key(kind, name));
    }

    /** Starts a source from its beginning (or restarts it) and writes its first state at once. */
    synchronized Run start(Kind kind, String name) {
        Run run = new Run(kind, name, clock.instant());
        Run previous = runs.get(key(kind, name));
        if (previous != null) run.speed = previous.speed;
        runs.put(key(kind, name), run);
        apply(run);
        return run;
    }

    synchronized void stop(Kind kind, String name) {
        runs.remove(key(kind, name));
    }

    synchronized void pause(String name, boolean paused) {
        Run run = runs.get(key(Kind.CHUNK, name));
        if (run != null) run.paused = paused;
    }

    synchronized void speed(String name, double speed) {
        if (!SPEEDS.contains(speed)) throw new IllegalArgumentException("Speed must be one of " + SPEEDS);
        Run run = runs.get(key(Kind.CHUNK, name));
        if (run != null) run.speed = speed;
    }

    /** Advances chunks and re-applies every running source. */
    synchronized void tick() {
        Instant now = clock.instant();
        for (Run run : runs.values()) {
            if (run.kind == Kind.CHUNK && !run.paused) {
                Duration step = Duration.between(run.lastTick, now);
                run.position = run.position.plusMillis(Math.round(step.toMillis() * run.speed));
                try {
                    Duration length = DevSources.chunkLength(json, root, run.name);
                    if (run.position.compareTo(length) > 0) run.position = length;
                } catch (Exception ignored) {
                    // apply() reports an unreadable chunk.
                }
            }
            run.lastTick = now;
            apply(run);
        }
    }

    private void apply(Run run) {
        try {
            Instant now = clock.instant();
            DevSources.State state = run.kind == Kind.SCENARIO
                    ? DevSources.scenario(json, root, run.name, run.startedAt, Duration.between(run.startedAt, now))
                    : DevSources.chunk(json, root, run.name, run.startedAt, run.position);
            if (!state.games().isEmpty()) games.recordDev(state.league(), state.games(), now);
            int players = 0;
            for (FeedGame game : state.games()) {
                List<StatLine> lines = state.lines().getOrDefault(game.providerId(), List.of());
                if (!lines.isEmpty()) stats.recordGame(game.id(), new ArrayList<>(lines), now);
                players += lines.size();
            }
            run.games = state.games().size();
            run.players = players;
            run.error = null;
        } catch (Exception exception) {
            // A half-edited file is normal while you work on it: keep the last good state and say what's wrong.
            run.error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            log.debug("Dev sports source {} failed", run.name, exception);
        }
    }
}
