package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.security.MutationAuthentication;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Spring context, containers and HTTP helpers for integration tests.
 * Subclasses must not change the context configuration, so one context is reused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Import(IntegrationTestSupport.AdminProbeConfiguration.class)
public abstract class IntegrationTestSupport {
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

    static {
        // Started once per JVM so every subclass shares one cached Spring context.
        postgres.start();
        redis.start();
        kafka.start();
    }

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Tests drive locking explicitly; a background worker would race their assertions.
        registry.add("pvpicks.bet-lock-worker.enabled", () -> "false");
        registry.add("PVPICKS_DEV_JWT_SECRET", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PVPICKS_DEV_IDEMPOTENCY_SECRET", () -> "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=");
    }

    @Autowired
    protected TestRestTemplate client;

    @LocalServerPort
    protected int serverPort;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected RedisConnectionFactory redisConnectionFactory;

    @Autowired
    protected KafkaAdmin kafkaAdmin;

    @Autowired
    protected Flyway flyway;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected PasswordEncoder passwords;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JwtDecoder decoder;

    @Autowired
    protected PlatformTransactionManager transactions;

    protected void appendDebtDelta(UUID bet, UUID table, UUID user, UUID other, long userDelta) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> jdbc.update("""
                INSERT INTO ledger_entries (id, bet_id, table_id, user_id, delta_cents)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), bet, table, user, userDelta,
                UUID.randomUUID(), bet, table, other, -userDelta));
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatSendPost(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return post("/tables/" + table + "/messages").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected JsonNode chatWindow(UUID table, Cookie session, String parameter, String value) throws Exception {
        var request = get("/chat/" + table + "/messages").cookie(session);
        if (parameter != null) request.param(parameter, value);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder debtCapPut(
            Cookie session, Csrf csrf, String key, String body) {
        return put("/me/debt-cap").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected MvcResult groupHttpAction(Cookie cookie, Csrf csrf, UUID other, String step, String revision, String key)
            throws Exception {
        return mvc.perform(post("/people/" + other + "/settle").cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"" + step + "\",\"expectedRevision\":\"" + revision + "\"}")).andReturn();
    }

    protected String groupRevision(Cookie cookie) throws Exception {
        return json.readTree(mvc.perform(get("/people/settle-groups").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).path("revision").asText();
    }

    protected UUID insertDebtBet(UUID table, UUID user, UUID other, String status, boolean forFun,
            boolean picked, boolean pointsSettled, long wager, long userDelta) {
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents,
                                  is_4fun, status, locks_at, locked_at, remainder_rule, points_settled_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Debt?', '["Yes","No"]'::jsonb, ?, ?, ?,
                        now(), CASE WHEN ? = 'ACTIVE' THEN NULL ELSE now() END,
                        CASE WHEN ? = 'ACTIVE' THEN NULL ELSE 'earliest' END,
                        CASE WHEN ? THEN now() ELSE NULL END)
                """, bet, table, other, wager, forFun, status, status, status, pointsSettled);
        if (picked) {
            jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, user);
            jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, user);
        }
        if (userDelta != 0) {
            appendDebtDelta(bet, table, user, other, userDelta);
        }
        return bet;
    }

    protected UUID insertDebtReceipt(
            UUID table, UUID host, UUID payer, UUID payee, long amount, String status) {
        UUID settlement = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlement, table, host);
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, line, settlement, table, host, payer, payee, amount, status);
        return line;
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invitationResponse(
            UUID invite, Cookie session, Csrf csrf, String key, boolean accept) {
        return post("/invites/" + invite + "/respond").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accept\":" + accept + "}");
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invitePreferencesPut(
            Cookie session, Csrf csrf, String key, String body) {
        return put("/me/invite-preferences").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected Cookie loginAs(String username) throws Exception {
        Csrf csrf = csrf();
        MvcResult login = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        return session;
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder quickPickRequest(
            HttpMethod method, String path, Cookie session, Csrf csrf, String key, String body) {
        var builder = request(method, path).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return builder;
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reactionPut(
            UUID message, Cookie session, Csrf csrf, String key, String body) {
        return reactionRequest(HttpMethod.PUT, message, session, csrf, key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reactionRequest(
            HttpMethod method, UUID message, Cookie session, Csrf csrf, String key) {
        return request(method, "/messages/" + message + "/reaction").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }

    protected MvcResult receiptHttpAction(Cookie cookie, Csrf csrf, UUID line, String action, int revision, String key)
            throws Exception {
        return mvc.perform(post("/settlement-lines/" + line + "/" + action).cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + "}")).andReturn();
    }

    /**
     * Runs one client test file through real HTTP against this test's server (see
     * client/src/features/auth/backend-integration-utils.ts). Requires installed client dependencies.
     */
    protected void runFrontendTest(String file, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("npm", "test", "--", file)
                .directory(Path.of("../../client").toFile())
                .inheritIO();
        builder.environment().put("PVPICKS_TEST_PORT", Integer.toString(serverPort));
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("frontend integration timed out").isTrue();
            assertThat(process.exitValue()).as("frontend integration exit code").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    protected String reviewRevision(Cookie cookie, UUID other) throws Exception {
        JsonNode review = json.readTree(mvc.perform(get("/users/" + other + "/settlement-review").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("SettlementReview").validate(review)).isEmpty();
        return review.path("pendingReviewRevision").isNull() ? null : review.path("pendingReviewRevision").asText();
    }

    protected void verifyMutationExpiry(Jwt validated, java.util.function.Function<Jwt, ?> mutation)
            throws Exception {
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(validated.getSubject()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> mutation.apply(expiring));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    protected record Csrf(Cookie cookie, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminProbeConfiguration {
        // Registered as a member class of this test configuration; component scanning skips it.
        @RestController
        static class AdminProbeController {
            @RequestMapping(path = "/admin/security-probe", method = {RequestMethod.GET, RequestMethod.POST})
            ResponseEntity<Void> probe() {
                return ResponseEntity.noContent().build();
            }
        }
    }
}
