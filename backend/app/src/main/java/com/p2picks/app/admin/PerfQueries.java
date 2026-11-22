package com.p2picks.app.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads {@code perf_minutes} into charts and a per-route table for the admin Perf tab. */
@Component
class PerfQueries {
    /** A time window and the width of each chart point in it. */
    enum Window {
        HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1)),
        SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(5)),
        DAY("24h", Duration.ofHours(24), Duration.ofMinutes(15)),
        WEEK("7d", Duration.ofDays(7), Duration.ofHours(1));

        final String id;
        final Duration length;
        final Duration step;

        Window(String id, Duration length, Duration step) {
            this.id = id;
            this.length = length;
            this.step = step;
        }

        static Window of(String id) {
            for (Window window : values()) if (window.id.equals(id)) return window;
            throw new IllegalArgumentException("window is 1h, 6h, 24h or 7d");
        }
    }

    record Stats(long requests, long serverErrors, long clientErrors, long rateLimited, Integer avgMs, Integer p50,
            Integer p95, Integer p99, int maxMs) {}

    record Point(Instant at, Stats stats) {}

    record RouteStats(String method, String route, Stats stats) {}

    record Report(String window, String source, Instant from, Instant to, long stepSeconds, String method,
            String route, Stats totals, List<Point> series, List<RouteStats> routes) {}

    private static final class Sum {
        long requests;
        long serverErrors;
        long clientErrors;
        long rateLimited;
        long totalMs;
        int maxMs;
        final long[] buckets = new long[PerfBuckets.SIZE];

        void add(Sum other) {
            requests += other.requests;
            serverErrors += other.serverErrors;
            clientErrors += other.clientErrors;
            rateLimited += other.rateLimited;
            totalMs += other.totalMs;
            maxMs = Math.max(maxMs, other.maxMs);
            for (int i = 0; i < buckets.length; i++) buckets[i] += other.buckets[i];
        }

        Stats stats() {
            return new Stats(requests, serverErrors, clientErrors, rateLimited,
                    requests == 0 ? null : (int) Math.round((double) totalMs / requests),
                    PerfBuckets.quantile(buckets, 0.50, maxMs), PerfBuckets.quantile(buckets, 0.95, maxMs),
                    PerfBuckets.quantile(buckets, 0.99, maxMs), maxMs);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    PerfQueries(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The window's totals, one chart point per step (empty steps included, so gaps show as zero) and every route
     * busiest first. With {@code method} and {@code route}, the totals and chart cover only that route.
     */
    Report report(Window window, PerfRecorder.Source source, String method, String route) {
        long step = window.step.toSeconds();
        Instant to = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Instant from = Instant.ofEpochSecond(Math.floorDiv(to.minus(window.length).getEpochSecond(), step) * step);
        boolean filtered = method != null && route != null;

        // One row per route and chart point, with the histogram summed element by element.
        Map<Instant, Sum> points = new LinkedHashMap<>();
        for (Instant at = from; !at.isAfter(to); at = at.plusSeconds(step)) points.put(at, new Sum());
        Map<String, Sum> routes = new LinkedHashMap<>();
        Sum totals = new Sum();
        jdbc.query("""
                WITH rows AS (
                    SELECT to_timestamp(floor(extract(epoch FROM minute) / ?) * ?) AS at, method, route, requests,
                           server_errors, client_errors, rate_limited, total_ms, max_ms, buckets
                    FROM perf_minutes WHERE source = ? AND minute >= ? AND minute < ?
                ), sums AS (
                    SELECT at, method, route, sum(requests) AS requests, sum(server_errors) AS server_errors,
                           sum(client_errors) AS client_errors, sum(rate_limited) AS rate_limited,
                           sum(total_ms) AS total_ms, max(max_ms) AS max_ms
                    FROM rows GROUP BY at, method, route
                ), histograms AS (
                    SELECT at, method, route, array_agg(total ORDER BY i) AS buckets
                    FROM (SELECT at, method, route, i, sum(v) AS total
                          FROM rows, unnest(buckets) WITH ORDINALITY AS u(v, i) GROUP BY at, method, route, i) b
                    GROUP BY at, method, route
                )
                SELECT s.*, h.buckets FROM sums s JOIN histograms h USING (at, method, route)
                """, rs -> {
                    Sum sum = new Sum();
                    sum.requests = rs.getLong("requests");
                    sum.serverErrors = rs.getLong("server_errors");
                    sum.clientErrors = rs.getLong("client_errors");
                    sum.rateLimited = rs.getLong("rate_limited");
                    sum.totalMs = rs.getLong("total_ms");
                    sum.maxMs = rs.getInt("max_ms");
                    Object[] buckets = (Object[]) rs.getArray("buckets").getArray();
                    for (int i = 0; i < Math.min(buckets.length, PerfBuckets.SIZE); i++) {
                        sum.buckets[i] = ((Number) buckets[i]).longValue();
                    }
                    String rowMethod = rs.getString("method");
                    String rowRoute = rs.getString("route");
                    routes.computeIfAbsent(rowMethod + " " + rowRoute, ignored -> new Sum()).add(sum);
                    if (filtered && !(rowMethod.equals(method) && rowRoute.equals(route))) return;
                    totals.add(sum);
                    Instant at = rs.getTimestamp("at").toInstant();
                    points.computeIfAbsent(at, ignored -> new Sum()).add(sum);
                }, step, step, source.name().toLowerCase(), Timestamp.from(from), Timestamp.from(to.plusSeconds(60)));

        List<Point> series = new ArrayList<>();
        points.forEach((at, sum) -> series.add(new Point(at, sum.stats())));
        List<RouteStats> table = new ArrayList<>();
        routes.forEach((key, sum) -> table.add(new RouteStats(key.substring(0, key.indexOf(' ')),
                key.substring(key.indexOf(' ') + 1), sum.stats())));
        table.sort(Comparator.comparingLong((RouteStats row) -> row.stats().requests()).reversed());
        return new Report(window.id, source.name().toLowerCase(), from, to, step, filtered ? method : null,
                filtered ? route : null, totals.stats(), series, table);
    }
}
