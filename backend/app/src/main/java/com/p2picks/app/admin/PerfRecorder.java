package com.p2picks.app.admin;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collects request timings in memory, one accumulator per minute, source, method and route, and writes finished
 * minutes to {@code perf_minutes} (adding to a row another instance already wrote). Rows older than
 * {@link #RETENTION} are deleted. Each minute holds at most {@link #MAX_KEYS} routes per source, so a client
 * reporting junk can't grow the table without bound; extra routes in that minute are dropped.
 */
@Component
class PerfRecorder {
    static final Duration RETENTION = Duration.ofDays(7);
    static final int MAX_KEYS = 300;
    private static final Logger log = LoggerFactory.getLogger(PerfRecorder.class);

    enum Source { SERVER, CLIENT }

    record Key(Instant minute, Source source, String method, String route) {}

    static final class Totals {
        int requests;
        int serverErrors;
        int clientErrors;
        int rateLimited;
        long totalMs;
        int maxMs;
        final long[] buckets = new long[PerfBuckets.SIZE];

        synchronized void add(int status, long ms) {
            requests++;
            if (status >= 500) serverErrors++;
            else if (status == 429) rateLimited++;
            else if (status >= 400) clientErrors++;
            totalMs += ms;
            maxMs = (int) Math.max(maxMs, Math.min(ms, Integer.MAX_VALUE));
            buckets[PerfBuckets.index(ms)]++;
        }
    }

    private final Map<Key, Totals> open = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> keysPerMinute = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    PerfRecorder(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records one request. {@code status} is the HTTP status (0 when the client got no response). */
    void record(Source source, String method, String route, int status, long ms) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Key key = new Key(minute, source, method, route);
        Totals totals = open.get(key);
        if (totals == null) {
            AtomicInteger count = keysPerMinute.computeIfAbsent(minute + "|" + source, ignored -> new AtomicInteger());
            if (count.incrementAndGet() > MAX_KEYS) return;
            totals = open.computeIfAbsent(key, ignored -> new Totals());
        }
        totals.add(status, Math.max(0, ms));
    }

    /** Writes every finished minute. */
    @Scheduled(fixedDelayString = "${pvpicks.perf.flush:PT15S}", initialDelayString = "PT15S")
    void flush() {
        flushBefore(clock.instant().truncatedTo(ChronoUnit.MINUTES));
    }

    /** Writes everything, including the current minute (tests and shutdown). */
    void flushAll() {
        flushBefore(Instant.MAX);
    }

    private void flushBefore(Instant cutoff) {
        List<Map.Entry<Key, Totals>> done = new ArrayList<>();
        for (Map.Entry<Key, Totals> entry : open.entrySet()) {
            if (entry.getKey().minute().isBefore(cutoff)) done.add(entry);
        }
        for (Map.Entry<Key, Totals> entry : done) {
            open.remove(entry.getKey());
            try {
                write(entry.getKey(), entry.getValue());
            } catch (RuntimeException exception) {
                log.warn("Dropped perf bucket {}: {}", entry.getKey(), exception.getMessage());
            }
        }
        keysPerMinute.keySet().removeIf(key -> Instant.parse(key.substring(0, key.indexOf('|'))).isBefore(cutoff));
    }

    private void write(Key key, Totals totals) {
        synchronized (totals) {
            Integer[] buckets = new Integer[PerfBuckets.SIZE];
            for (int i = 0; i < buckets.length; i++) buckets[i] = (int) totals.buckets[i];
            jdbc.execute((java.sql.Connection connection) -> {
                Array array = connection.createArrayOf("integer", buckets);
                try (var statement = connection.prepareStatement("""
                        INSERT INTO perf_minutes (minute, source, method, route, requests, server_errors,
                                                  client_errors, rate_limited, total_ms, max_ms, buckets)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (minute, source, method, route) DO UPDATE SET
                            requests = perf_minutes.requests + EXCLUDED.requests,
                            server_errors = perf_minutes.server_errors + EXCLUDED.server_errors,
                            client_errors = perf_minutes.client_errors + EXCLUDED.client_errors,
                            rate_limited = perf_minutes.rate_limited + EXCLUDED.rate_limited,
                            total_ms = perf_minutes.total_ms + EXCLUDED.total_ms,
                            max_ms = greatest(perf_minutes.max_ms, EXCLUDED.max_ms),
                            buckets = ARRAY(SELECT a + b FROM unnest(perf_minutes.buckets, EXCLUDED.buckets) AS t(a, b))
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(key.minute()));
                    statement.setString(2, key.source().name().toLowerCase());
                    statement.setString(3, key.method());
                    statement.setString(4, key.route());
                    statement.setInt(5, totals.requests);
                    statement.setInt(6, totals.serverErrors);
                    statement.setInt(7, totals.clientErrors);
                    statement.setInt(8, totals.rateLimited);
                    statement.setLong(9, totals.totalMs);
                    statement.setInt(10, totals.maxMs);
                    statement.setArray(11, array);
                    return statement.executeUpdate();
                }
            });
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    void deleteExpired() {
        int deleted = jdbc.update("DELETE FROM perf_minutes WHERE minute < ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
        if (deleted > 0) log.debug("Deleted {} expired perf rows", deleted);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        try {
            flushAll();
        } catch (RuntimeException exception) {
            log.warn("Could not save perf data on shutdown: {}", exception.getMessage());
        }
    }
}
