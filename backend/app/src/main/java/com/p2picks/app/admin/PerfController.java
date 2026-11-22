package com.p2picks.app.admin;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Performance: browsers report what they measured ({@code POST /perf}, any signed-in account), and admins read
 * the stored minutes ({@code GET /admin/perf}) and live runtime gauges ({@code GET /admin/perf/live}).
 */
@RestController
class PerfController {
    static final int MAX_SAMPLES = 100;
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** A client page route such as {@code /tables/$tableId}: lowercase segments and {@code $name} placeholders. */
    private static final Pattern PAGE = Pattern.compile("^/([a-z0-9-]+|\\$[a-zA-Z]+)?(/([a-z0-9-]+|\\$[a-zA-Z]+)){0,4}$");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sample(String kind, String method, String path, Integer status, Long ms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Samples(List<Sample> samples) {}

    record Live(Instant checkedAt, Instant startedAt, long uptimeSeconds, String host, String version,
            Database database, Jvm jvm, Outbox outbox, List<Feed> feeds) {}

    record Database(Integer active, Integer idle, Integer waiting, Integer max) {}

    record Jvm(long heapUsedBytes, long heapMaxBytes, int threads, Double processCpu, int processors,
            String javaVersion) {}

    record Outbox(long pending, Long oldestPendingSeconds) {}

    record Feed(String league, Instant lastSuccessAt, Instant lastFailureAt, String lastFailure) {}

    private final PerfRecorder recorder;
    private final PerfRoutes routes;
    private final PerfQueries queries;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    PerfController(PerfRecorder recorder, PerfRoutes routes, PerfQueries queries, JdbcTemplate jdbc,
            DataSource dataSource) {
        this.recorder = recorder;
        this.routes = routes;
        this.queries = queries;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * Browser timings, at most {@value #MAX_SAMPLES} per call: {@code api} (an API call, filed under the server
     * route it matched; unknown paths are dropped), {@code page} (a full page load) and {@code lcp} (largest
     * contentful paint), the last two under the app route such as {@code /tables/$tableId}.
     */
    @PostMapping("/perf")
    ResponseEntity<?> report(@RequestBody(required = false) Samples body) {
        if (body == null || body.samples() == null || body.samples().size() > MAX_SAMPLES) {
            return error(400, "bad_request", "Send 0–" + MAX_SAMPLES + " samples.");
        }
        for (Sample sample : body.samples()) {
            if (sample == null || sample.kind() == null || sample.ms() == null || sample.ms() < 0
                    || sample.ms() > 600_000) continue;
            switch (sample.kind()) {
                case "api" -> {
                    if (sample.method() == null || !METHODS.contains(sample.method())) continue;
                    String path = sample.path() == null ? null : sample.path().split("\\?", 2)[0];
                    Optional<String> route = routes.route(sample.method(), path);
                    if (route.isEmpty() || route.get().equals("/perf")) continue;
                    int status = sample.status() == null ? 0 : sample.status();
                    // No response (network failure or timeout) counts as a server-side failure.
                    if (status == 0) status = 599;
                    if (status < 100 || status > 599) continue;
                    recorder.record(PerfRecorder.Source.CLIENT, sample.method(), route.get(), status, sample.ms());
                }
                case "page", "lcp" -> {
                    if (sample.path() == null || !PAGE.matcher(sample.path()).matches()) continue;
                    recorder.record(PerfRecorder.Source.CLIENT, sample.kind().toUpperCase(), sample.path(), 200,
                            sample.ms());
                }
                default -> { }
            }
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/admin/perf")
    ResponseEntity<?> perf(@RequestParam(defaultValue = "1h") String window,
            @RequestParam(defaultValue = "server") String source, @RequestParam(required = false) String method,
            @RequestParam(required = false) String route) {
        PerfQueries.Window parsed;
        try {
            parsed = PerfQueries.Window.of(window);
        } catch (IllegalArgumentException invalid) {
            return error(400, "bad_request", invalid.getMessage());
        }
        if (!source.equals("server") && !source.equals("client")) {
            return error(400, "bad_request", "source is server or client.");
        }
        if ((method == null) != (route == null)) return error(400, "bad_request", "Send method and route together.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.report(parsed,
                PerfRecorder.Source.valueOf(source.toUpperCase()), method, route));
    }

    @GetMapping("/admin/perf/live")
    ResponseEntity<Live> live() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Double cpu = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad();
            cpu = load < 0 ? null : load;
        }
        Database database = new Database(null, null, null, null);
        if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
            var pool = hikari.getHikariPoolMXBean();
            database = new Database(pool.getActiveConnections(), pool.getIdleConnections(),
                    pool.getThreadsAwaitingConnection(), hikari.getMaximumPoolSize());
        }
        Outbox outbox = jdbc.queryForObject("""
                SELECT count(*) AS pending, extract(epoch FROM now() - min(created_at))::bigint AS oldest
                FROM outbox WHERE published_at IS NULL
                """, (rs, row) -> new Outbox(rs.getLong("pending"), (Long) rs.getObject("oldest", Long.class)));
        List<Feed> feeds = jdbc.query("""
                SELECT league, last_success_at, last_failure_at, last_failure FROM sports_feed_status ORDER BY league
                """, (rs, row) -> new Feed(rs.getString("league"), instant(rs.getTimestamp("last_success_at")),
                        instant(rs.getTimestamp("last_failure_at")), rs.getString("last_failure")));
        Instant started = Instant.ofEpochMilli(runtime.getStartTime());
        Live live = new Live(Instant.now(), started, Duration.between(started, Instant.now()).toSeconds(), host(),
                PerfController.class.getPackage().getImplementationVersion(), database,
                new Jvm(memory.getUsed(), memory.getMax(), ManagementFactory.getThreadMXBean().getThreadCount(), cpu,
                        Runtime.getRuntime().availableProcessors(), Runtime.version().toString()),
                outbox, feeds);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(live);
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException unknown) {
            return null;
        }
    }
}
