package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.modules.identity.internal.ModuleDiscoveryProbe;
import com.p2picks.modules.bets.api.U2PickSettler;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
@AutoConfigureMockMvc
@Import(P2PicksApplicationTest.AdminProbeConfiguration.class)
class P2PicksApplicationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    private static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

    @DynamicPropertySource
    static void serviceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("PVPICKS_DEV_JWT_SECRET", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PVPICKS_DEV_IDEMPOTENCY_SECRET", () -> "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=");
    }

    @Autowired
    private TestRestTemplate client;

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private TableReadController tableReads;

    @Autowired
    private SettingsReadController settingsReads;

    @Autowired
    private SocialReadController socialReads;

    @Autowired
    private UserSearchController userSearch;

    @Autowired
    private UserLookupController userLookup;

    @Autowired
    private InvitePreferencesService invitePreferences;

    @Autowired
    private DebtCapService debtCaps;

    @Autowired
    private BetLockService betLocks;

    @Autowired
    private BetPickService betPicks;

    @Autowired
    private U2PickProposalService u2PickProposals;

    @Autowired
    private U2PickResolutionService u2PickResolution;

    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Autowired
    private SettlementNetReconciliation settlementReconciliation;

    @Autowired
    private SettlementReceiptService settlementReceipts;

    @Autowired
    private SettlementGroupService settlementGroups;

    @Autowired
    private ReactionService reactions;

    @Autowired
    private ChatReadService chatRead;

    @Autowired
    private ChatWindowService chatWindows;

    @Autowired
    private ChatSendService chatSend;

    @Autowired
    private TablePermissionService tablePermissions;

    @Autowired
    private TableRemainderService tableRemainder;

    @Autowired
    private TableSettingsService tableSettings;

    @Autowired
    private TableQuickPickService tableQuickPicks;

    @Autowired
    private TableMemberRemovalService tableMemberRemovals;

    @Autowired
    private TableArchiveService tableArchives;

    @Autowired
    private UserProfileService userProfiles;

    @Autowired
    private TableProfileService tableProfile;

    @Autowired
    private TableHostService tableHosts;

    @Autowired
    private FeedbackService feedbackService;

    @Autowired
    private SocialMutationService socialMutations;

    @Autowired
    private InvitationResponseService invitationResponses;

    @Autowired
    private TableCreationService tableCreation;

    @Autowired
    private IdempotencyGuard idempotency;

    @Autowired
    private ModuleDiscoveryProbe moduleDiscoveryProbe;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void exposesHealthWithoutDependencyDetails() {
        ResponseEntity<JsonNode> response = client.getForEntity("/actuator/health", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }

    @Test
    void developerDocsArePublicInDevWithoutOpeningOtherApis() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/docs/"));
        mvc.perform(get("/docs/")).andExpect(status().isOk()).andExpect(forwardedUrl("/docs/index.html"));
        MvcResult home = mvc.perform(get("/docs/index.html")).andExpect(status().isOk()).andReturn();
        assertThat(home.getResponse().getContentAsString()).contains("Engineering handbook", "/docs/assets/");
        ResponseEntity<String> servedHome = client.getForEntity("/docs/", String.class);
        assertThat(servedHome.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(servedHome.getBody()).contains("Engineering handbook");
        ResponseEntity<String> architecture = client.getForEntity("/docs/architecture.html", String.class);
        assertThat(architecture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(architecture.getBody()).contains("Architecture", "/docs/assets/");
        MvcResult reference = mvc.perform(get("/docs/source/openapi.yaml")).andExpect(status().isOk()).andReturn();
        assertThat(reference.getResponse().getContentAsString()).contains("openapi:");
        mvc.perform(get("/docs/source/.env.auth")).andExpect(status().isNotFound());
        mvc.perform(get("/tables")).andExpect(status().isUnauthorized());
    }

    @Test
    void connectsToPostgresRedisAndKafka() throws Exception {
        assertThat(moduleDiscoveryProbe).isNotNull();
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            assertThat(admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS)).isNotBlank();
        }
    }

    @Test
    void migratesAndEnforcesUserIdentityAndDeletionConstraints() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "sample_user", 1, "test-hash", "PLAYER", "ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT id FROM users WHERE username = ?::citext", UUID.class, "SAMPLE_USER"))
                .isEqualTo(userId);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), "sample_user", 1, "test-hash", "PLAYER", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET username = ? WHERE id = ?", "Bad Name", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET hue = NULL WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET status = 'DELETED', deleted_at = now() WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(
                        "UPDATE users SET username = NULL, hue = NULL, password_hash = NULL, status = 'DELETED', deleted_at = now() WHERE id = ?",
                        userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ? AND username IS NULL", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void storesSessionsWithValidLifetimeAndExistingUser() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "session_user", 1, "test-hash", "PLAYER", "ACTIVE");
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                sessionId, userId, now, now.plusHours(8));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM auth_sessions WHERE id = ? AND revoked_at IS NULL",
                        Integer.class, sessionId))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), userId, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), UUID.randomUUID(), now, now.plusHours(8)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void socialRelationshipsAreDirectedUniqueAndCannotReferenceSelfOrMissingUsers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'social_first', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'social_second', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, first, second);

        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, second);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", second, first);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, second))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", first, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM follows WHERE follower_id IN (?, ?)", first, second);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, second);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", second, first);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, second))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", first, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tablesSupportMultipleHostsAndRetainUniqueQuickPickCodes() {
        UUID tableId = UUID.randomUUID();
        UUID firstHost = UUID.randomUUID();
        UUID secondHost = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'table_host1', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'table_host2', 2, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'table_member', 3, 'test-hash', 'PLAYER', 'ACTIVE')
                """, firstHost, secondHost, member);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Test table", "T", "K4R7QX");
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role)
                VALUES (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, tableId, firstHost, tableId, secondHost, tableId, member);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM table_members WHERE table_id = ? AND role = 'HOST'",
                        Integer.class, tableId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, tableId))
                .isEqualTo("K4R7QX");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), "Duplicate", "T", "K4R7QX"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE tables SET quickpick_code = 'INVALID' WHERE id = ?", tableId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE tables SET quickpick_sharing = true WHERE id = ?", tableId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?",
                        tableId, firstHost))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                        tableId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tableInvitationsAllowOnlyOnePendingInvitePerRecipient() {
        UUID tableId = UUID.randomUUID();
        UUID inviter = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'invite_host', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'invite_target', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, inviter, recipient);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Invite table", "T", "N4R7QX");
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                inviteId, tableId, inviter, recipient);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, recipient))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, inviter))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE table_invites SET status = 'DECLINED' WHERE id = ?", inviteId);
        assertThat(jdbc.update(
                        "INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        UUID.randomUUID(), tableId, inviter, recipient))
                .isEqualTo(1);
    }

    @Test
    void quickPickSubscriptionsAreDirectedUniqueAndReferenceDifferentTables() {
        UUID source = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Source', 'T', 'Q4R7QX'), (?, 'Subscriber', 'T', 'R4R7QX')
                """, source, subscriber);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriber, source);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                source, subscriber);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        subscriber, source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        source, source))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                        subscriber, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxAndDomainChangesCommitOrRollBackTogether() {
        UUID source = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Outbox source', 'T', 'S4R7QX'), (?, 'Outbox subscriber', 'T', 'T4R7QX')
                """, source, subscriber);
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                    subscriber, source);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                    """, eventId, subscriber.toString());
            throw new IllegalStateException("rollback test");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, eventId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriber, source))
                .isZero();

        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                    subscriber, source);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'table', ?, 'example.v1', '{"version": 1}'::jsonb)
                    """, eventId, subscriber.toString());
        });
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'version' FROM outbox WHERE id = ? AND published_at IS NULL",
                        String.class, eventId))
                .isEqualTo("1");
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriber, source))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                        """, eventId, subscriber.toString()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void betParticipationKeepsNoGuessSeparateAndSnapshotsTheHostAtLock() {
        UUID userId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID betId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'bet_host', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Bets", "T", "X4R7QX");
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Next play?', '["Pass","Run"]'::jsonb,
                        100, false, now())
                """, betId, tableId, userId);
        assertThatThrownBy(() -> jdbc.update("UPDATE bets SET wager_cents = 9 WHERE id = ?", betId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bets SET wager_cents = 1001 WHERE id = ?", betId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE bets SET wager_cents = 10 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET wager_cents = 1000 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET is_4fun = true, wager_cents = 0 WHERE id = ?", betId);
        jdbc.update("UPDATE bets SET is_4fun = false, wager_cents = 100 WHERE id = ?", betId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?",
                        Integer.class, betId)).isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Pass')", betId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", betId, userId);
        Long order = jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Pass')", betId, userId);
        jdbc.update("DELETE FROM picks WHERE bet_id = ? AND user_id = ?", betId, userId);
        assertThat(jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId)).isEqualTo(order);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, '__no_guess__')", betId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Run')", betId, userId);
        assertThat(jdbc.queryForObject("""
                SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?
                """, Long.class, betId, userId)).isEqualTo(order);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'host' WHERE id = ?
                """, betId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE bets SET remainder_rule = 'host', remainder_host_id = ? WHERE id = ?
                """, userId, betId)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(),
                                remainder_rule = 'host', remainder_host_id = ? WHERE id = ?
                """, userId, betId);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, betId))
                .isEqualTo(userId);
    }

    @Test
    void quickPickCopiesReferenceTheirActualSourceAndAreUniqueAtTheTarget() {
        UUID userId = UUID.randomUUID();
        UUID sourceTable = UUID.randomUUID();
        UUID targetTable = UUID.randomUUID();
        UUID unrelatedTable = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'copy_author', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Source bet', 'T', 'Y4R7QX'), (?, 'Target bet', 'T', 'Z4R7QX'),
                       (?, 'Unrelated', 'T', '234567')
                """, sourceTable, targetTable, unrelatedTable);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Next play?', '["Pass","Run"]'::jsonb,
                        100, false, now())
                """, original, sourceTable, userId);
        String insertCopy = """
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at, source_bet_id, source_table_id)
                SELECT ?, ?, proposer_id, mode, config, question, choices,
                       wager_cents, is_4fun, locks_at, ?, ?
                FROM bets WHERE id = ?
                """;
        jdbc.update(insertCopy, UUID.randomUUID(), targetTable, original, sourceTable, original);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, sourceTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, unrelatedTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), sourceTable, original, sourceTable, original))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertCopy, UUID.randomUUID(), targetTable, original, null, original))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settlementHistoryRetainsLineIdentityAndBetAssociations() {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        UUID laterSettlement = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'settle_host', 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'settle_member', 2, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, 'settle_other', 3, 'test-hash', 'PLAYER', 'ACTIVE')
                """, host, member, other);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'Settle', 'T', '345678'), (?, 'Other settle', 'T', '456789')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?), (?, ?, ?)
                """, settlement, table, host, laterSettlement, table, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now())
                """, bet, table, host);
        jdbc.update("INSERT INTO settlement_bets (bet_id, table_id, settlement_id) VALUES (?, ?, ?)",
                bet, table, settlement);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO settlement_bets (bet_id, table_id, settlement_id) VALUES (?, ?, ?)",
                        bet, table, laterSettlement))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM settlement_bets WHERE bet_id = ?", bet))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET host_id = ? WHERE id = ?", member, settlement))
                .isInstanceOf(DataIntegrityViolationException.class);

        String insertLine = """
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        jdbc.update(insertLine, line, settlement, table, host, member, host, 100);
        jdbc.update(insertLine, UUID.randomUUID(), laterSettlement, table, host, host, member, 50);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, host, member, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, member, other, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, otherTable, host, other, host, 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insertLine, UUID.randomUUID(), settlement, table, host, other, host, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE settlement_lines SET status = 'sent', revision = 1, updated_at = now() WHERE id = ?", line);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("sent");
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_lines SET revision = -1 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE settlement_lines SET status = 'unknown' WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                UPDATE settlement_lines SET status = 'forgive_asked', forgive_requested_at = now(),
                                            revision = 2, updated_at = now() WHERE id = ?
                """, line);
        assertThat(jdbc.queryForObject(
                        "SELECT forgive_requested_at FROM settlement_lines WHERE id = ?", OffsetDateTime.class, line))
                .isNotNull();
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE settlement_lines SET amount_cents = 200 WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM settlement_lines WHERE id = ?", line))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void debtCapsStoreOffZeroAndPositiveValuesWithoutAllowingNegativeCaps() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'cap_user', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, user);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, NULL)", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isNull();
        jdbc.update("UPDATE debt_caps SET cap_cents = 0 WHERE user_id = ?", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isZero();
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", user);
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isEqualTo(100);
        assertThatThrownBy(() -> jdbc.update("UPDATE debt_caps SET cap_cents = -1 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upgradesPopulatedVersionTwoThroughTwentyFiveDatabases() {
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        Flyway initial = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("2")).load();
        assertThat(initial.migrate().migrationsExecuted).isEqualTo(2);
        JdbcTemplate upgradedJdbc = new JdbcTemplate(
                new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
        UUID userId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'existing_user', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);

        Flyway social = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("3")).load();
        assertThat(social.migrate().migrationsExecuted).isEqualTo(1);
        UUID secondUserId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'second_user', 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, secondUserId);
        upgradedJdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", userId, secondUserId);

        Flyway tables = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("4")).load();
        assertThat(tables.migrate().migrationsExecuted).isEqualTo(1);
        UUID tableId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                tableId, "Existing table", "T", "V4R7QX");
        upgradedJdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')",
                tableId, userId);

        Flyway subscriptions = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("5")).load();
        assertThat(subscriptions.migrate().migrationsExecuted).isEqualTo(1);
        UUID subscriberId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, ?, ?)",
                subscriberId, "Existing subscriber", "T", "W4R7QX");
        upgradedJdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriberId, tableId);

        Flyway outbox = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("6")).load();
        assertThat(outbox.migrate().migrationsExecuted).isEqualTo(1);
        UUID eventId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'table', ?, 'example.v1', '{}'::jsonb)
                """, eventId, tableId.toString());

        Flyway bets = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("7")).load();
        assertThat(bets.migrate().migrationsExecuted).isEqualTo(1);
        UUID betId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now())
                """, betId, tableId, userId);

        Flyway ledger = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("8")).load();
        assertThat(ledger.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO ledger_entries (id, bet_id, table_id, user_id, delta_cents)
                VALUES (?, ?, ?, ?, 100), (?, ?, ?, ?, -100)
                """, UUID.randomUUID(), betId, tableId, userId,
                UUID.randomUUID(), betId, tableId, secondUserId);

        Flyway settlements = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("9")).load();
        assertThat(settlements.migrate().migrationsExecuted).isEqualTo(1);
        UUID settlementId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)",
                settlementId, tableId, userId);

        Flyway chat = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("10")).load();
        assertThat(chat.migrate().migrationsExecuted).isEqualTo(1);
        UUID messageId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Existing message')
                """, messageId, tableId, userId);

        Flyway notifications = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("11")).load();
        assertThat(notifications.migrate().migrationsExecuted).isEqualTo(1);
        UUID notificationId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone, title)
                VALUES (?, ?, ?, 'existing', 'invite', 'Existing notification')
                """, notificationId, userId, UUID.randomUUID());

        Flyway feedback = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("12")).load();
        assertThat(feedback.migrate().migrationsExecuted).isEqualTo(1);
        UUID feedbackId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO feedback (id, user_id, kind, text)
                VALUES (?, ?, 'bug', 'Existing feedback')
                """, feedbackId, userId);

        Flyway adminFlags = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("13")).load();
        assertThat(adminFlags.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("INSERT INTO admin_user_flags (user_id, flagged, strike_count) VALUES (?, true, 2)",
                userId);

        Flyway processedEvents = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("14")).load();
        assertThat(processedEvents.migrate().migrationsExecuted).isEqualTo(1);
        UUID processedEventId = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO processed_events (consumer_name, event_id) VALUES ('test', ?)",
                processedEventId);

        Flyway archives = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("15")).load();
        assertThat(archives.migrate().migrationsExecuted).isEqualTo(1);
        UUID archivedId = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at)
                VALUES (?, 'Archived', 'T', 'V5R7QX', now())
                """, archivedId);
        upgradedJdbc.update("INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", archivedId, userId);

        Flyway retries = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("16")).load();
        assertThat(retries.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status)
                VALUES (?, decode(repeat('00', 32), 'hex'), decode(repeat('11', 32), 'hex'), 'existing', 204)
                """, userId);

        Flyway invitationPreferences = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("17")).load();
        assertThat(invitationPreferences.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("INSERT INTO invite_preferences (user_id, friends, following) VALUES (?, 'closed', 'open')",
                userId);
        UUID legacyFeedback = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO feedback (id, user_id, kind, text, link)
                VALUES (?, ?, 'gif', 'Existing feedback', ?)
                """, legacyFeedback, userId, "https://x/\uFEFFy");

        Flyway feedbackLinks = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("18")).load();
        assertThat(feedbackLinks.migrate().migrationsExecuted).isEqualTo(1);
        UUID validInvite = UUID.randomUUID();
        UUID staleInvite = UUID.randomUUID();
        upgradedJdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')",
                subscriberId, secondUserId);
        upgradedJdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, validInvite, subscriberId, secondUserId, userId, staleInvite, tableId, secondUserId, userId);
        Flyway invitations = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("19")).load();
        assertThat(invitations.migrate().migrationsExecuted).isEqualTo(1);
        upgradedJdbc.update("""
                INSERT INTO chat_read_positions (table_id, user_id, last_read_order)
                SELECT table_id, ?, message_order FROM chat_messages WHERE id = ?
                """, secondUserId, messageId);
        UUID legacyChatNotice = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone,
                                           title, table_id, actor_id, source_message_id)
                VALUES (?, ?, ?, 'legacy-chat', 'chat', 'Chat', ?, ?, ?)
                """, legacyChatNotice, secondUserId, UUID.randomUUID(), tableId, userId, messageId);
        Flyway readState = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("20")).load();
        assertThat(readState.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id = ?",
                Boolean.class, legacyChatNotice)).isTrue();
        UUID delayedChatNotice = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone,
                                           title, table_id, actor_id, source_message_id)
                VALUES (?, ?, ?, 'delayed-chat', 'chat', 'Chat', ?, ?, ?)
                """, delayedChatNotice, secondUserId, UUID.randomUUID(), tableId, userId, messageId);
        assertThat(upgradedJdbc.queryForObject("SELECT read_at IS NOT NULL FROM notifications WHERE id = ?",
                Boolean.class, delayedChatNotice)).isTrue();
        Flyway retryReferences = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("21")).load();
        assertThat(retryReferences.migrate().migrationsExecuted).isEqualTo(1);
        UUID legacySystem = UUID.randomUUID();
        upgradedJdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'Existing system line')
                """, legacySystem, tableId);
        Flyway hostAttribution = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("22")).load();
        assertThat(hostAttribution.migrate().migrationsExecuted).isEqualTo(1);
        Flyway updated = Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load();
        assertThat(updated.migrate().migrationsExecuted).isEqualTo(4);
        assertThatThrownBy(() -> upgradedJdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgradedJdbc.queryForObject("""
                SELECT text FROM chat_messages WHERE id = ? AND system_event IS NULL AND system_subject_id IS NULL
                """, String.class, legacySystem)).isEqualTo("Existing system line");
        assertThat(upgradedJdbc.queryForObject(
                "SELECT count(*) FROM http_idempotency WHERE response_resource_id IS NOT NULL", Integer.class)).isZero();
        assertThat(upgradedJdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, validInvite))
                .isEqualTo("PENDING");
        assertThat(upgradedJdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, staleInvite))
                .isEqualTo("CANCELLED");
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject(
                        "SELECT count(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
                        Integer.class, userId, secondUserId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM blocks", Integer.class)).isZero();
        assertThat(upgradedJdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?",
                        String.class, tableId))
                .isEqualTo("V4R7QX");
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ?",
                        Integer.class, tableId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("""
                        SELECT count(*) FROM quickpick_subscriptions
                        WHERE subscriber_table_id = ? AND source_table_id = ?
                        """, Integer.class, subscriberId, tableId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM bets WHERE id = ?", Integer.class, betId))
                .isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?",
                        Integer.class, betId)).isEqualTo(2);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM settlements WHERE id = ?",
                        Integer.class, settlementId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE id = ?",
                        Integer.class, messageId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notifications')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notification_preferences')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT to_regclass('notification_muted_tables')", String.class)).isNull();
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM chat_read_positions WHERE table_id = ? AND user_id = ?",
                Integer.class, tableId, secondUserId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM feedback WHERE id = ?",
                        Integer.class, feedbackId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT link FROM feedback WHERE id = ?", String.class, legacyFeedback))
                .isEqualTo("https://x/\uFEFFy");
        assertThat(upgradedJdbc.queryForObject("SELECT strike_count FROM admin_user_flags WHERE user_id = ?",
                        Integer.class, userId)).isEqualTo(2);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, processedEventId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM tables WHERE id = ? AND archived_at IS NULL",
                        Integer.class, tableId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject(
                "SELECT count(*) FROM table_archive_members WHERE table_id = ? AND user_id = ?",
                Integer.class, archivedId, userId)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT count(*) FROM http_idempotency", Integer.class)).isEqualTo(1);
        assertThat(upgradedJdbc.queryForObject("SELECT friends FROM invite_preferences WHERE user_id = ?",
                String.class, userId)).isEqualTo("closed");
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status)
                VALUES (?, decode(repeat('22', 32), 'hex'), decode(repeat('33', 32), 'hex'), 'created', 201)
                """, userId);
        upgradedJdbc.update("""
                INSERT INTO http_idempotency (user_id, key_digest, request_digest, operation, response_status, response_data)
                VALUES (?, decode(repeat('44', 32), 'hex'), decode(repeat('55', 32), 'hex'), 'members', 200, '[]'::jsonb)
                """, userId);
        assertThat(updated.migrate().migrationsExecuted).isZero();
    }

    @Test
    void loginRequiresOriginAndCsrfAndLogoutRevokesSession() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "login_user", 2, passwords.encode("a secure test password"), "PLAYER", "ACTIVE");
        Csrf csrf = csrf();
        String login = "{\"username\":\" LOGIN_USER \",\"password\":\"a secure test password\"}";
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://evil.example")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .cookie(csrf.cookie()).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"login_user\",\"password\":\"a secure test password\",\"extra\":true}"))
                .andExpect(status().isBadRequest());
        MvcResult loggedIn = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = loggedIn.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        Jwt jwt = decoder.decode(session.getValue());
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(java.time.Duration.ofHours(8));
        assertThat(loggedIn.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).contains("SameSite=Strict", "Max-Age=28800"));
        MvcResult me = mvc.perform(get("/me").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(me.getResponse().getContentAsString()).path("username").asText()).isEqualTo("login_user");
        Cookie tampered = new Cookie("session", "x" + session.getValue().substring(1));
        mvc.perform(get("/me").cookie(tampered)).andExpect(status().isUnauthorized());
        Csrf logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(session, logoutCsrf.cookie()))
                .andExpect(status().isForbidden());
        MvcResult replacement = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()).cookie(session, logoutCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isNoContent()).andReturn();
        Cookie replacementSession = replacement.getResponse().getCookie("session");
        assertThat(replacementSession).isNotNull();
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
        session = replacementSession;
        logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()).cookie(session, logoutCsrf.cookie()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongCredentialsAndDisablesFrozenAndExpiredSessions() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "frozen_user", 1, passwords.encode("a secure test password"), "PLAYER", "ACTIVE");
        Csrf csrf = csrf();
        String origin = "http://127.0.0.1:8080";
        MvcResult wrong = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknown = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown_user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        MvcResult login = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", userId);
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
        Csrf frozenCsrf = csrf();
        MvcResult frozen = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", frozenCsrf.token()).cookie(frozenCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(frozen.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbc.update("""
                UPDATE auth_sessions SET created_at = now() - interval '1 minute', expires_at = now() - interval '1 second'
                WHERE user_id = ?
                """, userId);
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesRequireAnActiveAdministratorAndUseTheCurrentDatabaseRole() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'roleplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'roleadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, userId, passwords.encode("a secure test password"),
                adminId, passwords.encode("a secure test password"));
        mvc.perform(get("/admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/users/" + userId + "/audit")).andExpect(status().isUnauthorized());

        Csrf loginCsrf = csrf();
        MvcResult login = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", loginCsrf.token()).cookie(loginCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"roleplayer\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        mvc.perform(get("/admin").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/health").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security-probe").cookie(session)).andExpect(status().isForbidden());
        Csrf actionCsrf = csrf();
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tables").cookie(session)).andExpect(status().isOk());

        session = loginAs("roleadmin");
        mvc.perform(get("/admin/security-probe").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/me").cookie(session)).andExpect(status().isOk());
        mvc.perform(get("/tables").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(post("/tables").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token())
                        .header("Idempotency-Key", "admin-create")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Blocked\",\"emoji\":\"🏈\"}"))
                .andExpect(status().isForbidden());
        MvcResult health = mvc.perform(get("/admin/health").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(health.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode snapshot = json.readTree(health.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("AdminHealthSnapshot").validate(snapshot)).isEmpty();
        assertThat(snapshot.path("postgres").asText()).isEqualTo("UP");
        assertThat(snapshot.path("redis").asText()).isEqualTo("UP");
        assertThat(snapshot.path("kafka").asText()).isEqualTo("NOT_CHECKED");
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token()))
                .andExpect(status().isNoContent());
        Cookie anotherAdminSession = loginAs("roleadmin");
        Csrf logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").cookie(anotherAdminSession, logoutCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/me").cookie(anotherAdminSession)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", adminId);
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", adminId);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", adminId);
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void accountRolesAreImmutableWithoutBlockingOtherUpdates() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'fixedplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'fixedadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, player, hash, admin, hash);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", player))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET role = 'PLAYER' WHERE id = ?", admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE users SET role = role, hue = 2 WHERE id = ?", player)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", admin)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, player))
                .isEqualTo("PLAYER");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, admin))
                .isEqualTo("ADMIN");
    }

    @Test
    void betReadsProtectActivePicksAndRevealLockedBetsForBothModeFamilies() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID watcher = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID emptyTable = UUID.randomUUID();
        UUID customBet = UUID.randomUUID();
        UUID sportsBet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betreadhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadwatcher', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadoutside', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, host, hash, member, hash, watcher, hash, outsider, hash, admin, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Bet reads', 'T', 'Z9R6QA'), (?, 'Empty bets', 'T', 'Z9R5QA')
                """, table, emptyTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, host, table, member, table, watcher, emptyTable, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, game_id, config, question, choices,
                                  wager_cents, is_4fun, locks_at, winning_choice, called_by)
                VALUES (?, ?, ?, 'u2pick', NULL, '{}'::jsonb, 'Custom?', '["Yes","No"]'::jsonb,
                        100, false, now() + interval '1 minute', 'Yes', ?),
                       (?, ?, ?, 'either_or', 'game-1', '{}'::jsonb, 'Sports?', '["Pass","Run"]'::jsonb,
                        100, false, now() + interval '1 minute', NULL, NULL)
                """, customBet, table, host, host, sportsBet, table, host);
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?), (?, ?)
                """, customBet, host, customBet, member, customBet, watcher, sportsBet, member);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No'), (?, ?, 'Pass')
                """, customBet, member, customBet, watcher, sportsBet, member);
        Cookie hostSession = loginAs("betreadhost");
        Cookie memberSession = loginAs("betreadmember");
        Cookie outsideSession = loginAs("betreadoutside");
        Cookie adminSession = loginAs("betreadadmin");

        mvc.perform(get("/tables/" + table + "/bets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tables/" + table + "/bets").cookie(outsideSession)).andExpect(status().isNotFound());
        mvc.perform(get("/bets/" + customBet).cookie(outsideSession)).andExpect(status().isNotFound());
        mvc.perform(get("/bets/" + customBet).cookie(adminSession)).andExpect(status().isForbidden());
        MvcResult empty = mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(empty.getResponse().getContentAsString())).isEmpty();
        MvcResult list = mvc.perform(get("/tables/" + table + "/bets").cookie(memberSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode bets = json.readTree(list.getResponse().getContentAsString());
        assertThat(bets.size()).isEqualTo(2);
        for (JsonNode bet : bets) {
            assertThat(OpenApiSchemas.load("Bet").validate(bet)).isEmpty();
            assertThat(bet.has("picks")).isFalse();
            assertThat(bet.has("noGuessUserIds")).isFalse();
            assertThat(bet.has("calledBy")).isFalse();
            assertThat(bet.has("winningChoice")).isFalse();
            assertThat(bet.path("myParticipation").asText()).isEqualTo("picked");
            assertThat(bet.path("myPick").asText())
                    .isEqualTo(bet.path("id").asText().equals(customBet.toString()) ? "Yes" : "Pass");
        }
        JsonNode hostActive = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostActive.path("myParticipation").asText()).isEqualTo("no_guess");
        assertThat(hostActive.path("myPick").isNull()).isTrue();
        assertThat(hostActive.path("pickCount").asInt()).isEqualTo(2);

        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'host',
                                remainder_host_id = ? WHERE id IN (?, ?)
                """, host, customBet, sportsBet);
        JsonNode locked = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(locked)).isEmpty();
        assertThat(locked.path("picks").size()).isEqualTo(2);
        assertThat(locked.path("noGuessUserIds").size()).isEqualTo(1);
        assertThat(locked.path("noGuessUserIds").get(0).asText()).isEqualTo(host.toString());
        assertThat(locked.has("winningChoice")).isFalse();
        assertThat(locked.path("remainderHostId").asText()).isEqualTo(host.toString());
        JsonNode lockedSports = json.readTree(mvc.perform(get("/bets/" + sportsBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(lockedSports)).isEmpty();
        assertThat(lockedSports.path("myParticipation").asText()).isEqualTo("not_joined");
        assertThat(lockedSports.path("picks").get(0).path("choice").asText()).isEqualTo("Pass");

        jdbc.update("UPDATE bets SET status = 'WASHED', closed_at = now(), wash_reason = 'No winner' WHERE id = ?",
                sportsBet);
        JsonNode washed = json.readTree(mvc.perform(get("/bets/" + sportsBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(washed)).isEmpty();
        assertThat(washed.path("washReason").asText()).isEqualTo("No winner");
        assertThat(washed.path("deltasCents").isEmpty()).isTrue();

        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.update("UPDATE bets SET status = 'SETTLED', closed_at = now() WHERE id = ?", customBet);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents) VALUES
                    (?, ?, ?, ?, 100), (?, ?, ?, ?, -100)
                    """, UUID.randomUUID(), table, customBet, member,
                    UUID.randomUUID(), table, customBet, watcher);
        });
        JsonNode resolved = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(resolved)).isEmpty();
        assertThat(resolved.path("winningChoice").asText()).isEqualTo("Yes");
        assertThat(resolved.path("deltasCents").path(member.toString()).asLong()).isEqualTo(100);
        assertThat(resolved.path("deltasCents").path(watcher.toString()).asLong()).isEqualTo(-100);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, member);
        mvc.perform(get("/bets/" + customBet).cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table + "/bets").cookie(memberSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/bets/" + customBet).cookie(hostSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table + "/bets").cookie(hostSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", host);
        mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", host);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void tableReadsEnforceMembershipAndRedactHostOnlyData() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID limited = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'tablehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tablemember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tablelimited', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tableadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, host, hash, member, hash, limited, hash, admin, hash);
        UUID table = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'Visible', 'T', 'H2R5QA', true, true),
                       (?, 'Archived', 'T', 'J2R5QA', false, false),
                       (?, 'Other', 'T', 'K2R5QA', true, true)
                """, table, archived, source);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_bet, can_text, can_react)
                VALUES (?, ?, 'HOST', true, true, true),
                       (?, ?, 'MEMBER', true, false, false),
                       (?, ?, 'MEMBER', false, true, true),
                       (?, ?, 'HOST', true, true, true),
                       (?, ?, 'HOST', true, true, true)
                """, table, host, table, member, table, limited, archived, host, source, admin);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        jdbc.update("INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", archived, host);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                table, source);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, UUID.randomUUID(), source, admin, member);
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now() + interval '1 day')
                """, bet, table, host);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, limited);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, limited);

        mvc.perform(get("/tables")).andExpect(status().isUnauthorized());
        Cookie hostSession = loginAs("tablehost");
        Cookie memberSession = loginAs("tablemember");
        Cookie adminSession = loginAs("tableadmin");
        MvcResult hostList = mvc.perform(get("/tables").cookie(hostSession)).andExpect(status().isOk()).andReturn();
        assertThat(hostList.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode hostTables = json.readTree(hostList.getResponse().getContentAsString());
        assertThat(hostTables.size()).isEqualTo(1);
        JsonNode hostTable = hostTables.get(0);
        assertThat(OpenApiSchemas.load("Table").validate(hostTable)).isEmpty();
        assertThat(hostTable.path("id").asText()).isEqualTo(table.toString());
        assertThat(hostTable.path("quickPickCode").asText()).isEqualTo("H2R5QA");
        assertThat(hostTable.path("disabledPermissions").path(member.toString()).toString())
                .isEqualTo("[\"text\",\"react\"]");
        assertThat(hostTable.path("disabledPermissions").path(limited.toString()).toString())
                .isEqualTo("[\"bets\"]");
        assertThat(hostTable.path("subscribedTo").get(0).asText()).isEqualTo(source.toString());
        assertThat(hostTable.has("participants")).isFalse();
        assertThat(hostTable.has("picks")).isFalse();
        mvc.perform(get("/tables/" + archived).cookie(hostSession)).andExpect(status().isNotFound());

        MvcResult memberGet = mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(memberGet.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode memberTable = json.readTree(memberGet.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(memberTable)).isEmpty();
        assertThat(memberTable.has("quickPickCode")).isFalse();
        assertThat(memberTable.path("disabledPermissions").path(member.toString()).toString())
                .isEqualTo("[\"text\",\"react\"]");
        assertThat(memberTable.path("disabledPermissions").has(limited.toString())).isFalse();
        mvc.perform(get("/tables/" + source).cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table).cookie(adminSession)).andExpect(status().isForbidden());
        mvc.perform(get("/tables/" + source).cookie(adminSession)).andExpect(status().isForbidden());
        mvc.perform(get("/tables/" + UUID.randomUUID()).cookie(memberSession)).andExpect(status().isNotFound());

        jdbc.update("""
                UPDATE table_members SET role = 'HOST', can_text = true, can_react = true
                WHERE table_id = ? AND user_id = ?
                """, table, member);
        JsonNode promoted = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(promoted.path("quickPickCode").asText()).isEqualTo("H2R5QA");
        assertThat(promoted.path("disabledPermissions").has(limited.toString())).isTrue();
        jdbc.update("""
                UPDATE table_members SET role = 'MEMBER', can_text = false
                WHERE table_id = ? AND user_id = ?
                """, table, member);
        JsonNode demoted = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(demoted.has("quickPickCode")).isFalse();
        assertThat(demoted.path("disabledPermissions").has(limited.toString())).isFalse();
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, member);
        mvc.perform(get("/tables/" + table).cookie(memberSession)).andExpect(status().isNotFound());
        JsonNode memberTables = json.readTree(mvc.perform(get("/tables").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(memberTables.isEmpty()).isTrue();
        Jwt validatedBeforeRevocation = decoder.decode(memberSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", member);
        assertThat(tableReads.list(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/tables").cookie(memberSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void debtReadIncludesCurrentExposureAndOpenPersonNetsOnly() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID losing = UUID.randomUUID();
        UUID winning = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debthost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debtpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash, third, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Losing', 'T', 'D6R3QA'), (?, 'Winning', 'T', 'D6R4QA'),
                (?, 'Archived', 'T', 'D6R5QA'), (?, 'Left', 'T', 'D6R6QA')
                """, losing, winning, archived, left);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, losing, user, winning, user, archived, user);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        UUID corrected = insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, -300);
        appendDebtDelta(corrected, losing, user, other, 50);
        insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, 100);
        insertDebtBet(losing, user, other, "PENDING", false, true, false, 150, 0);
        insertDebtBet(losing, user, other, "PENDING", false, false, false, 700, 0);
        insertDebtBet(losing, user, other, "PENDING", true, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "ACTIVE", false, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "SETTLED", false, false, true, 100, -900);
        insertDebtBet(winning, user, other, "SETTLED", false, false, false, 100, 9000);
        insertDebtBet(archived, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtBet(left, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtReceipt(archived, other, user, other, 400, "sent");
        insertDebtReceipt(archived, other, other, user, 100, "unpaid");
        insertDebtReceipt(archived, other, user, other, 10, "unpaid");
        insertDebtReceipt(archived, other, user, other, 20, "disputed");
        insertDebtReceipt(archived, other, user, other, 30, "forgive_asked");
        insertDebtReceipt(archived, third, third, user, 9000, "unpaid");
        insertDebtReceipt(archived, other, user, other, 9000, "paid");
        insertDebtReceipt(archived, other, user, other, 9000, "forgiven");
        mvc.perform(get("/me/debt")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("debtviewer");
        MvcResult response = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode debt = json.readTree(response.getResponse().getContentAsString());
        assertThat(debt.size()).isEqualTo(1);
        assertThat(debt.path("debtCents").asLong()).isEqualTo(660);
        Cookie thirdSession = loginAs("debtpeer");
        JsonNode thirdDebt = json.readTree(mvc.perform(get("/me/debt").cookie(thirdSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(thirdDebt.path("debtCents").asLong()).isEqualTo(9000);
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(settingsReads.debt(validated).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void internalU2PickLifecycleMovesFromProposalToLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'flowhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'flowplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'U2Pick flow', 'T', 'Y8S3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie hostCookie = loginAs("flowhost");
        Cookie playerCookie = loginAs("flowplayer");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt playerJwt = decoder.decode(playerCookie.getValue());
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition("Who?", List.of("Yes", "No")),
                json.createObjectNode(), 125, 30, false, U2PickSettler.Rule.HOSTS);
        UUID bet = u2PickProposals.create(hostJwt, table, proposal, "flow-propose").betId();
        assertThat(bet).isNotNull();
        assertThat(betPicks.change(hostJwt, bet, "Yes", "flow-host-pick")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(betPicks.change(playerJwt, bet, "No", "flow-player-pick")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Yes", "flow-too-early"))
                .isInstanceOf(U2PickResolutionService.Closed.class);
        JsonNode active = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(active.path("myPick").asText()).isEqualTo("Yes");
        assertThat(active.path("pickCount").asInt()).isEqualTo(2);
        assertThat(active.has("picks")).isFalse();
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        JsonNode pending = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("picks").size()).isEqualTo(2);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "flow-result"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        JsonNode result = json.readTree(mvc.perform(get("/bets/" + bet).cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("status").asText()).isEqualTo("SETTLED");
        assertThat(result.path("pointsSettled").asBoolean()).isFalse();
        assertThat(result.path("deltasCents").path(host.toString()).asLong()).isEqualTo(125);
        assertThat(result.path("deltasCents").path(player.toString()).asLong()).isEqualTo(-125);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(125);
        var settlement = pointsSettlement.settle(hostJwt, table, "flow-points");
        assertThat(settlement.outcome()).isEqualTo(TablePointsSettlementService.Outcome.CREATED);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, settlement.settlementId(), player, host)).isEqualTo(125);
        JsonNode settled = json.readTree(mvc.perform(get("/bets/" + bet).cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(settled.path("pointsSettled").asBoolean()).isTrue();
        debt = json.readTree(mvc.perform(get("/me/debt").cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(125);
    }

    @Test
    void gatedBetHttpAdaptersValidateAndRunTheU2PickLifecycle() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betapiplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betapioutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bet HTTP', 'T', 'H4B3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie hostCookie = loginAs("betapihost");
        Cookie playerCookie = loginAs("betapiplayer");
        Cookie outsideCookie = loginAs("betapioutside");
        Csrf csrf = csrf();
        String proposal = """
                {"modeId":"u2pick","config":{"question":"outdated","options":["old"],"tag":"kept"},
                 "question":"  Who wins?  ","choices":[" Yes ","No"],"wagerCents":125,
                 "durationSec":30,"forFun":false,"settledBy":"hosts"}
                """;
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "http-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(proposal)).andExpect(status().isForbidden());
        mvc.perform(post("/tables/" + table + "/bets").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-anonymous").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/tables/" + table + "/bets").cookie(outsideCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-outside").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isNotFound());
        for (String invalid : List.of(
                proposal.replace("\"u2pick\"", "\"either_or\""),
                proposal.replace("\"durationSec\":30", "\"durationSec\":29"),
                proposal.replace("\"wagerCents\":125", "\"wagerCents\":125.0"),
                proposal.replace("\"choices\":[\" Yes \",\"No\"]", "\"choices\":[\"Yes\",\" yes \"]"),
                proposal.replace("\"tag\":\"kept\"", "\"tag\":true"),
                proposal.replace("\"forFun\":false", "\"forFun\":false,\"unexpected\":1"))) {
            mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "http-invalid").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        MvcResult created = mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode betView = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(betView)).isEmpty();
        assertThat(betView.path("question").asText()).isEqualTo("Who wins?");
        assertThat(betView.path("config").path("question").asText()).isEqualTo("Who wins?");
        assertThat(betView.path("config").path("options")).isEqualTo(betView.path("choices"));
        UUID bet = UUID.fromString(betView.path("id").asText());
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(json.readTree(result.getResponse().getContentAsString()).path("id").asText())
                        .isEqualTo(bet.toString()));
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON)
                        .content(proposal.replace("Who wins?", "Who loses?"))).andExpect(status().isConflict());
        mvc.perform(delete("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-clear-before")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Integer.class, bet, player)).isZero();
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-invalid-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Maybe\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-host-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-clear-after")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Integer.class, bet, player)).isEqualTo(1);
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-repick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        JsonNode active = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(active.has("picks")).isFalse();
        assertThat(active.path("pickCount").asInt()).isEqualTo(2);
        mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-early-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-late-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isConflict());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isConflict());
        mvc.perform(post("/bets/" + bet + "/settle").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-not-settler").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Typo\"}")).andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "http-result").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet)).isZero();
        MvcResult points = mvc.perform(post("/tables/" + table + "/settle-points").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-points")).andExpect(status().isCreated()).andReturn();
        JsonNode pointLines = json.readTree(points.getResponse().getContentAsString()).path("lines");
        assertThat(pointLines.size()).isEqualTo(1);
        UUID receipt = UUID.fromString(pointLines.get(0).path("id").asText());
        assertThat(groupHttpAction(playerCookie, csrf, host, "sent", groupRevision(playerCookie), "http-group-sent")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(receiptHttpAction(hostCookie, csrf, receipt, "confirm", 1, "http-receipt-confirm")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, receipt))
                .isEqualTo("paid");
    }

    @Test
    void gatedPointsSettlementHttpRequiresAHostAndReturnsCurrentReceipts() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID zeroTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsapiplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsapiother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Points API', 'T', 'P3H4QA'), (?, 'Points API zero', 'T', 'P3H5QA')
                """, table, zeroTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, host, table, player, zeroTable, host);
        UUID bet = insertDebtBet(table, player, host, "SETTLED", false, false, false, 100, -125);
        insertDebtBet(zeroTable, player, host, "SETTLED", false, false, false, 100, -100);
        insertDebtBet(zeroTable, player, host, "SETTLED", false, false, false, 100, 100);
        Cookie hostCookie = loginAs("pointsapihost");
        Cookie playerCookie = loginAs("pointsapiplayer");
        Cookie outsideCookie = loginAs("pointsapiother");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/settle-points";
        mvc.perform(post(path).cookie(hostCookie).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "points-http-no-csrf")).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-anon"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token())).andExpect(status().isBadRequest());
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-body")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).cookie(playerCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-member"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(outsideCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-outsider"))
                .andExpect(status().isNotFound());
        MvcResult created = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode settlement = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Settlement").validate(settlement)).isEmpty();
        assertThat(settlement.path("tableId").asText()).isEqualTo(table.toString());
        assertThat(settlement.path("hostId").asText()).isEqualTo(host.toString());
        assertThat(settlement.path("lines").size()).isEqualTo(1);
        JsonNode line = settlement.path("lines").get(0);
        assertThat(line.path("payerId").asText()).isEqualTo(player.toString());
        assertThat(line.path("payeeId").asText()).isEqualTo(host.toString());
        assertThat(line.path("amountCents").asLong()).isEqualTo(125);
        UUID settlementId = UUID.fromString(settlement.path("id").asText());
        assertThat(pointsSettlement.readForHost(decoder.decode(playerCookie.getValue()), settlementId).settlement())
                .isNull();
        assertThat(pointsSettlement.readForHost(decoder.decode(outsideCookie.getValue()), settlementId).settlement())
                .isNull();
        assertThat(jdbc.queryForObject("SELECT settlement_id FROM settlement_bets WHERE bet_id = ?",
                UUID.class, bet)).isEqualTo(settlementId);
        jdbc.update("UPDATE settlement_lines SET status = 'sent', revision = revision + 1 WHERE id = ?",
                UUID.fromString(line.path("id").asText()));
        MvcResult replay = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isCreated()).andReturn();
        JsonNode replayed = json.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.path("id").asText()).isEqualTo(settlementId.toString());
        assertThat(replayed.path("lines").get(0).path("status").asText()).isEqualTo("sent");
        assertThat(replayed.path("lines").get(0).path("revision").asInt()).isEqualTo(1);
        MvcResult empty = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http-empty")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(empty.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("no_eligible_bets");
        String zeroPath = "/tables/" + zeroTable + "/settle-points";
        mvc.perform(post(zeroPath).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isConflict());
        MvcResult zero = mvc.perform(post(zeroPath).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http-zero")).andExpect(status().isCreated()).andReturn();
        JsonNode zeroSettlement = json.readTree(zero.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Settlement").validate(zeroSettlement)).isEmpty();
        assertThat(zeroSettlement.path("lines").isEmpty()).isTrue();
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isForbidden());
    }

    @Test
    void pointsSettlementRoutesLedgerAndReconcilesOpenPairClaims() throws Exception {
        UUID host = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID oldTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointshost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsloser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointswinner', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, loser, hash, winner, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Points', 'T', 'P2S3QA'), (?, 'Previous', 'T', 'P2S4QA')
                """, table, oldTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, loser, table, winner);
        UUID first = insertDebtBet(table, loser, winner, "SETTLED", false, false, false, 100, -125);
        UUID fun = insertDebtBet(table, loser, winner, "SETTLED", true, false, false, 100, 0);
        UUID pending = insertDebtBet(table, loser, winner, "PENDING", false, false, false, 100, 0);
        UUID sent = insertDebtReceipt(oldTable, host, loser, host, 40, "sent");
        UUID asked = insertDebtReceipt(oldTable, host, loser, host, 20, "forgive_asked");
        UUID paid = insertDebtReceipt(oldTable, host, loser, host, 1000, "paid");
        jdbc.update("UPDATE settlement_lines SET forgive_requested_at = now() WHERE id = ?", asked);
        Jwt hostJwt = decoder.decode(loginAs("pointshost").getValue());
        Jwt loserJwt = decoder.decode(loginAs("pointsloser").getValue());
        assertThat(pointsSettlement.settle(loserJwt, table, "not-host").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.FORBIDDEN);
        var result = pointsSettlement.settle(hostJwt, table, "points-key");
        assertThat(result.outcome()).isEqualTo(TablePointsSettlementService.Outcome.CREATED);
        assertThat(pointsSettlement.settle(hostJwt, table, "points-key"))
                .isEqualTo(new TablePointsSettlementService.Result(
                        TablePointsSettlementService.Outcome.REPLAYED, result.settlementId()));
        assertThat(pointsSettlement.settle(hostJwt, oldTable, "points-key").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> pointsSettlement.settle(hostJwt, table, "points-new"))
                .isInstanceOf(TablePointsSettlementService.NoEligibleBets.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE settlement_id = ?",
                Integer.class, result.settlementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT bet_id FROM settlement_bets WHERE settlement_id = ?",
                UUID.class, result.settlementId())).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, fun, pending)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE settlement_id = ?",
                Integer.class, result.settlementId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, result.settlementId(), loser, host)).isEqualTo(125);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, result.settlementId(), host, winner)).isEqualTo(125);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'unpaid' AND revision = 1",
                Integer.class, sent, asked)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT forgive_requested_at IS NULL FROM settlement_lines WHERE id = ?",
                Boolean.class, asked)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, paid))
                .isEqualTo("paid");
        JsonNode change = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                  AND payload->>'userId' = ? AND payload->>'otherUserId' = ?
                """, String.class, host.toString(), loser.toString()));
        assertThat(change.path("beforeCents").asLong()).isEqualTo(60);
        assertThat(change.path("afterCents").asLong()).isEqualTo(185);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE settlement_id = ? AND kind = 'settlement'",
                Integer.class, result.settlementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.created.v1'
                  AND payload->>'settlementId' = ?
                """, Integer.class, result.settlementId().toString())).isEqualTo(1);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        assertThat(pointsSettlement.settle(hostJwt, table, "points-key").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.FORBIDDEN);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void bettingPreviewRunsAgainstIsolatedBackend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betuihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betuiplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bet Preview', 'T', 'BP8X3A')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        ProcessBuilder builder = new ProcessBuilder("npm", "test", "--",
                "src/features/auth/betting-integration.test.tsx")
                .directory(Path.of("../../client").toFile())
                .inheritIO();
        builder.environment().put("PVPICKS_TEST_PORT", Integer.toString(serverPort));
        builder.environment().put("PVPICKS_TEST_TABLE_ID", table.toString());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("frontend integration timed out").isTrue();
            assertThat(process.exitValue()).as("frontend integration exit code").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        UUID bet = jdbc.queryForObject("SELECT id FROM bets WHERE table_id = ?", UUID.class, table);
        assertThat(bet).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(2);
        JsonNode hostView = json.readTree(mvc.perform(get("/bets/" + bet).cookie(loginAs("betuihost")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode playerView = json.readTree(mvc.perform(get("/bets/" + bet).cookie(loginAs("betuiplayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostView.path("myPick").asText()).isEqualTo("Cats");
        assertThat(playerView.path("myPick").asText()).isEqualTo("Dogs");
        assertThat(hostView.has("picks")).isFalse();
        assertThat(playerView.has("picks")).isFalse();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PVPICKS_FRONTEND_INTEGRATION", matches = "true")
    void settlementPreviewRunsAgainstIsolatedBackend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'previewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'previewpayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Preview', 'T', 'P4E8WA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -125);
        Jwt hostJwt = decoder.decode(loginAs("previewhost").getValue());
        pointsSettlement.settle(hostJwt, table, "preview-points");
        assertThat(reviewRevision(loginAs("previewpayer"), host)).isNotNull();

        ProcessBuilder builder = new ProcessBuilder("npm", "test", "--",
                "src/features/auth/settlement-integration.test.tsx")
                .directory(Path.of("../../client").toFile())
                .inheritIO();
        builder.environment().put("PVPICKS_TEST_PORT", Integer.toString(serverPort));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("frontend integration timed out").isTrue();
            assertThat(process.exitValue()).as("frontend integration exit code").isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertThat(reviewRevision(loginAs("previewpayer"), host)).isNull();
        assertThat(reviewRevision(loginAs("previewhost"), payer)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("sent");
    }

    @Test
    void settlementReviewIsPrivateRevisionCheckedAndRetainedAfterClosure() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reviewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewpayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Review', 'T', 'R8V3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -125);
        Cookie hostCookie = loginAs("reviewhost");
        Cookie payerCookie = loginAs("reviewpayer");
        Cookie outsiderCookie = loginAs("reviewother");
        Csrf csrf = csrf();
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        pointsSettlement.settle(hostJwt, table, "review-points-1");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("unpaid");
        String first = reviewRevision(payerCookie, host);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(first);
        mvc.perform(get("/people/settlement-reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/people/settlement-counterparties")).andExpect(status().isUnauthorized());
        assertThat(pendingReviews(outsiderCookie)).isEmpty();
        assertThat(json.readTree(mvc.perform(get("/people/settlement-counterparties").cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEmpty();
        JsonNode pending = pendingReviews(payerCookie);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).path("otherUserId").asText()).isEqualTo(host.toString());
        assertThat(pending.get(0).path("pendingReviewRevision").asText()).isEqualTo(first);
        mvc.perform(get("/users/" + host + "/settlement-review").cookie(outsiderCookie))
                .andExpect(status().isNotFound());
        JsonNode group = json.readTree(mvc.perform(get("/people/settle-groups").cookie(payerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(group.path("pendingReviewRevision").asText()).isEqualTo(first);
        assertThat(OpenApiSchemas.load("SettlementGroup").validate(group)).isEmpty();
        String groupRevision = group.path("revision").asText();
        for (String action : List.of("sent", "ask-forgive")) {
            MvcResult blocked = receiptHttpAction(payerCookie, csrf, line, action, 1, "review-line-" + action);
            assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_review_required");
        }
        for (String action : List.of("sent", "ask")) {
            MvcResult blocked = groupHttpAction(payerCookie, csrf, host, action, groupRevision, "review-group-" + action);
            assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_review_required");
        }
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(payerCookie, host)).isNull();
        assertThat(pendingReviews(payerCookie)).isEmpty();
        assertThat(pendingReviews(hostCookie).size()).isEqualTo(1);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(first);

        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -50);
        pointsSettlement.settle(hostJwt, table, "review-points-2");
        String second = reviewRevision(hostCookie, payer);
        assertThat(second).isNotEqualTo(first);
        assertThat(reviewRevision(payerCookie, host)).isNull();
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        MvcResult stale = ackReview(hostCookie, csrf, payer, first, "review-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_review");
        assertThat(receiptHttpAction(payerCookie, csrf, line, "sent", 1, "review-send-after-ack")
                .getResponse().getStatus()).isEqualTo(200);
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -25);
        pointsSettlement.settle(hostJwt, table, "review-points-3");
        String reset = reviewRevision(payerCookie, host);
        assertThat(reset).isNotEqualTo(first);
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(payerCookie, host)).isEqualTo(reset);
        assertThat(receiptHttpAction(hostCookie, csrf, line, "confirm", 3, "review-confirm")
                .getResponse().getStatus()).isEqualTo(200);
        String third = reviewRevision(hostCookie, payer);
        assertThat(third).isNotEqualTo(second);
        assertThat(groupHttpAction(hostCookie, csrf, payer, "forgiven", groupRevision(hostCookie), "review-close")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(third);
        assertThat(pendingReviews(hostCookie).get(0).path("pendingReviewRevision").asText()).isEqualTo(third);
        JsonNode closedParties = json.readTree(mvc.perform(get("/people/settlement-counterparties").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(closedParties.size()).isEqualTo(1);
        assertThat(closedParties.get(0).asText()).isEqualTo(payer.toString());
        JsonNode history = json.readTree(mvc.perform(get("/people/settle-groups").cookie(hostCookie)
                        .param("includeHistory", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(history.path("pendingReviewRevision").asText()).isEqualTo(third);
        assertThat(ackReview(hostCookie, csrf, payer, third, "review-ack-host").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(hostCookie, payer)).isNull();
        assertThat(pendingReviews(hostCookie)).isEmpty();
    }

    @Test
    void reviewAcknowledgmentWaitsForConcurrentSettlementAndRejectsOldRevision() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reviewracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewracepayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Review race', 'T', 'R8V4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -100);
        Cookie hostCookie = loginAs("reviewracehost");
        Cookie payerCookie = loginAs("reviewracepayer");
        Csrf csrf = csrf();
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        pointsSettlement.settle(hostJwt, table, "review-race-first");
        String first = reviewRevision(payerCookie, host);
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -50);
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var settlement = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                pointsSettlement.settle(hostJwt, table, "review-race-second");
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var acknowledgment = executor.submit(() -> ackReview(payerCookie, csrf, host, first, "review-race-ack"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            settlement.get(10, TimeUnit.SECONDS);
            MvcResult stale = acknowledgment.get(10, TimeUnit.SECONDS);
            assertThat(stale.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_review");
            assertThat(reviewRevision(payerCookie, host)).isNotEqualTo(first);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String reviewRevision(Cookie cookie, UUID other) throws Exception {
        JsonNode review = json.readTree(mvc.perform(get("/users/" + other + "/settlement-review").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("SettlementReview").validate(review)).isEmpty();
        return review.path("pendingReviewRevision").isNull() ? null : review.path("pendingReviewRevision").asText();
    }

    private JsonNode pendingReviews(Cookie cookie) throws Exception {
        MvcResult result = mvc.perform(get("/people/settlement-reviews").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode reviews = json.readTree(result.getResponse().getContentAsString());
        for (JsonNode review : reviews) {
            assertThat(OpenApiSchemas.load("PendingSettlementReview").validate(review)).isEmpty();
        }
        return reviews;
    }

    private MvcResult ackReview(Cookie cookie, Csrf csrf, UUID other, String revision, String key) throws Exception {
        return mvc.perform(post("/users/" + other + "/settlement-review/acknowledge").cookie(cookie, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":\"" + revision + "\"}")).andReturn();
    }

    @Test
    void pointsSettlementAllowsZeroNetAndRollsBackUnrepresentableTotals() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointszero', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'pointszeropeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Zero', 'T', 'P2Z3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID one = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        UUID two = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, 100);
        Jwt jwt = decoder.decode(loginAs("pointszero").getValue());
        var zero = pointsSettlement.settle(jwt, table, "zero-points");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE settlement_id = ?",
                Integer.class, zero.settlementId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE settlement_id = ?",
                Integer.class, zero.settlementId())).isZero();
        var chat = chatWindows.read(jwt, table, new ChatWindowService.Query(ChatWindowService.Direction.LATEST, null, null));
        assertThat(chat.window().items()).filteredOn(message -> zero.settlementId().equals(message.settlementId()))
                .singleElement().satisfies(message -> {
                    assertThat(message.settlementSummary().hostId()).isEqualTo(host);
                    assertThat(message.settlementSummary().lines()).isEmpty();
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, one, two)).isEqualTo(2);
        UUID huge = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, Long.MAX_VALUE);
        UUID extra = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, 1);
        assertThatThrownBy(() -> pointsSettlement.settle(jwt, table, "overflow-points"))
                .isInstanceOf(ArithmeticException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, huge, extra)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ? AND operation = 'table.points-settlement.v1'",
                Integer.class, host)).isEqualTo(1);
        appendDebtDelta(huge, table, peer, host, 1);
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        var exact = pointsSettlement.settle(jwt, table, "overflow-points");
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, exact.settlementId(), host, peer)).isEqualTo(2);
    }

    @Test
    void pointsSettlementRollsBackWhenReceiptReconciliationFails() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsrollback', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsrollpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Rollback', 'T', 'P2R3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID bet = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        UUID receipt = insertDebtReceipt(table, host, peer, host, 50, "sent");
        jdbc.update("UPDATE settlement_lines SET revision = 2147483647 WHERE id = ?", receipt);
        Jwt jwt = decoder.decode(loginAs("pointsrollback").getValue());
        assertThatThrownBy(() -> pointsSettlement.settle(jwt, table, "rollback-points"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT points_settled_at IS NULL FROM bets WHERE id = ?", Boolean.class, bet)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, receipt))
                .isEqualTo("sent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE bet_id = ?", Integer.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
        jdbc.update("UPDATE settlement_lines SET revision = 0 WHERE id = ?", receipt);
        assertThat(pointsSettlement.settle(jwt, table, "rollback-points").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.CREATED);
    }

    @Test
    void pointsSettlementRetriesIfResolutionAddsAffectedUsersDuringLocking() throws Exception {
        UUID host = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsracelose', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsracewin', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, loser, hash, winner, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'P2R4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID bet = insertDebtBet(table, loser, winner, "PENDING", false, false, false, 100, 0);
        Cookie cookie = loginAs("pointsracehost");
        Csrf csrf = csrf();
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                        (rs, row) -> rs.getObject("id", UUID.class), loser, winner);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
                jdbc.update("""
                        INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents)
                        VALUES (?, ?, ?, ?, -100), (?, ?, ?, ?, 100)
                        """, UUID.randomUUID(), table, bet, loser, UUID.randomUUID(), table, bet, winner);
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var settlement = executor.submit(() -> mvc.perform(post("/tables/" + table + "/settle-points")
                            .cookie(cookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                            .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "race-points"))
                    .andReturn());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            MvcResult raced = settlement.get(10, TimeUnit.SECONDS);
            assertThat(raced.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(raced.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_affected_users_changed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                    .isZero();
            mvc.perform(post("/tables/" + table + "/settle-points").cookie(cookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "race-points")).andExpect(status().isCreated());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void settlementReconciliationPreservesUnchangedAndFullyClosedGroups() {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsstable', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointstablepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Stable', 'T', 'P2S5QA')", table);
        UUID sent = insertDebtReceipt(table, host, peer, host, 100, "sent");
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                    (rs, row) -> rs.getObject("id", UUID.class), host, peer);
            var before = settlementReconciliation.capture(host, peer);
            insertDebtReceipt(table, host, host, peer, 30, "unpaid");
            insertDebtReceipt(table, host, peer, host, 30, "unpaid");
            settlementReconciliation.reconcile(before);
            assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, sent))
                    .isEqualTo("sent");
            var beforeClose = settlementReconciliation.capture(host, peer);
            jdbc.update("UPDATE settlement_lines SET status = 'paid' WHERE table_id = ?", table);
            settlementReconciliation.reconcile(beforeClose);
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                  AND payload->>'userId' = ?
                """, Integer.class, host.toString())).isZero();
    }

    @Test
    void pointsSettlementSerializesConcurrentMatchingRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsduel', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsduelpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Concurrent', 'T', 'P2D3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        Jwt jwt = decoder.decode(loginAs("pointsduel").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return pointsSettlement.settle(jwt, table, "duel-points");
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return pointsSettlement.settle(jwt, table, "duel-points");
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.outcome(), b.outcome())).containsExactlyInAnyOrder(
                    TablePointsSettlementService.Outcome.CREATED, TablePointsSettlementService.Outcome.REPLAYED);
            assertThat(a.settlementId()).isEqualTo(b.settlementId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void gatedReceiptHttpPreservesPrivateHistoryAndActionSemantics() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'rcapipayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcapipayee', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcapioutsider', 1, ?, 'PLAYER', 'ACTIVE')
                """, payer, hash, payee, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Receipt API', 'T', 'R5A2QA'), (?, 'Receipt API other', 'T', 'R5A3QA')
                """, table, otherTable);
        UUID line = insertDebtReceipt(table, payee, payer, payee, 300, "unpaid");
        UUID other = insertDebtReceipt(otherTable, payee, payer, payee, 50, "sent");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        Cookie payerCookie = loginAs("rcapipayer");
        Cookie payeeCookie = loginAs("rcapipayee");
        Cookie outsiderCookie = loginAs("rcapioutsider");
        Csrf csrf = csrf();
        String historyPath = "/users/" + payee + "/settlements";
        MvcResult history = mvc.perform(get(historyPath).cookie(payerCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(history.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode lines = json.readTree(history.getResponse().getContentAsString());
        assertThat(lines.size()).isEqualTo(2);
        for (JsonNode receipt : lines) assertThat(OpenApiSchemas.load("SettlementLine").validate(receipt)).isEmpty();
        JsonNode filtered = json.readTree(mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", table.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.get(0).path("id").asText()).isEqualTo(line.toString());
        assertThat(json.readTree(mvc.perform(get(historyPath).cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", table.toString(), otherTable.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(historyPath)).andExpect(status().isUnauthorized());
        String actionPath = "/settlement-lines/" + line + "/";
        mvc.perform(post(actionPath + "sent").cookie(payerCookie).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "rc-api-no-csrf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isForbidden());
        mvc.perform(post(actionPath + "sent").cookie(outsiderCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-out").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isNotFound());
        mvc.perform(post(actionPath + "sent").cookie(payeeCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-wrong-role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isForbidden());
        for (String invalid : List.of("{}", "{\"revision\":-1}", "{\"revision\":0.0}",
                "{\"revision\":2147483648}", "{\"revision\":0,\"extra\":true}")) {
            mvc.perform(post(actionPath + "sent").cookie(payerCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "rc-api-invalid").contentType(MediaType.APPLICATION_JSON)
                            .content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(actionPath + "unknown").cookie(payerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isBadRequest());
        MvcResult stale = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 1, "rc-api-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_revision");
        MvcResult asked = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 0, "rc-api-ask");
        assertThat(asked.getResponse().getStatus()).isEqualTo(200);
        assertThat(asked.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode askedLine = json.readTree(asked.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("SettlementLine").validate(askedLine)).isEmpty();
        assertThat(askedLine.path("status").asText()).isEqualTo("forgive_asked");
        assertThat(askedLine.path("forgiveRequestedAt").isTextual()).isTrue();
        MvcResult sent = receiptHttpAction(payerCookie, csrf, line, "sent", 1, "rc-api-sent");
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(sent.getResponse().getContentAsString()).path("forgiveRequestedAt"))
                .isEqualTo(askedLine.path("forgiveRequestedAt"));
        MvcResult replay = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 0, "rc-api-ask");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("status").asText()).isEqualTo("sent");
        assertThat(receiptHttpAction(payerCookie, csrf, line, "sent", 2, "rc-api-ask").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "dispute", 2, "rc-api-dispute").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "decline-forgive", 3, "rc-api-decline")
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult cooldown = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 4, "rc-api-cooldown");
        assertThat(cooldown.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(cooldown.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("invalid_transition");
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "forgive", 4, "rc-api-forgive").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(receiptHttpAction(payeeCookie, csrf, other, "confirm", 0, "rc-api-confirm").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("forgiven");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, other))
                .isEqualTo("paid");
    }

    private MvcResult receiptHttpAction(Cookie cookie, Csrf csrf, UUID line, String action, int revision, String key)
            throws Exception {
        return mvc.perform(post("/settlement-lines/" + line + "/" + action).cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + "}")).andReturn();
    }

    @Test
    void receiptHistoryAndIndividualActionsPreservePrivacyAndOtherClaims() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'receipthost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'receiptpayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'receiptother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Receipts', 'T', 'R4C3QA'), (?, 'Receipts other', 'T', 'R4C4QA')
                """, table, otherTable);
        UUID line = insertDebtReceipt(table, host, payer, host, 300, "unpaid");
        UUID other = insertDebtReceipt(otherTable, host, payer, host, 50, "sent");
        UUID paid = insertDebtReceipt(table, host, host, payer, 100, "paid");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        Jwt payerJwt = decoder.decode(loginAs("receiptpayer").getValue());
        Jwt hostJwt = decoder.decode(loginAs("receipthost").getValue());
        Jwt outsiderJwt = decoder.decode(loginAs("receiptother").getValue());
        var history = settlementReceipts.history(payerJwt, host, null);
        assertThat(history.authenticated()).isTrue();
        assertThat(history.player()).isTrue();
        assertThat(history.lines()).extracting(SettlementReceiptService.Line::id)
                .containsExactly(other, line, paid);
        assertThat(settlementReceipts.history(payerJwt, host, table).lines())
                .extracting(SettlementReceiptService.Line::id).containsExactly(line, paid);
        assertThat(settlementReceipts.history(outsiderJwt, host, null).lines()).isEmpty();
        assertThat(settlementReceipts.transition(outsiderJwt, line, SettlementReceiptService.Action.CONFIRM, 0, "rc-out")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.NOT_FOUND);
        assertThat(settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.SENT, 0, "rc-forbidden")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.FORBIDDEN);
        assertThatThrownBy(() -> settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 1, "rc-stale"))
                .isInstanceOf(SettlementReceiptService.StaleRevision.class);
        var asked = settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 0, "rc-ask");
        assertThat(asked.line().status()).isEqualTo("forgive_asked");
        assertThat(asked.line().forgiveRequestedAt()).isNotNull();
        var sent = settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 1, "rc-sent");
        assertThat(sent.line().status()).isEqualTo("sent");
        assertThat(sent.line().forgiveRequestedAt()).isEqualTo(asked.line().forgiveRequestedAt());
        assertThatThrownBy(() -> settlementReceipts.transition(hostJwt, line,
                SettlementReceiptService.Action.FORGIVE, 2, "rc-sent-forgive"))
                .isInstanceOf(SettlementReceiptService.InvalidTransition.class);
        var replay = settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.ASK_FORGIVE, 0, "rc-ask");
        assertThat(replay.outcome()).isEqualTo(SettlementReceiptService.Outcome.REPLAYED);
        assertThat(replay.line().status()).isEqualTo("sent");
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 2, "rc-ask")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.CONFLICT);
        var disputed = settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.DISPUTE, 2, "rc-dispute");
        assertThat(disputed.line().status()).isEqualTo("forgive_asked");
        var declined = settlementReceipts.transition(hostJwt, line,
                SettlementReceiptService.Action.DECLINE_FORGIVE, 3, "rc-decline");
        assertThat(declined.line().status()).isEqualTo("unpaid");
        assertThat(declined.line().forgiveRequestedAt()).isNull();
        assertThat(declined.line().forgiveDeclinedAt()).isNotNull();
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '23 hours 59 minutes'
                WHERE id = ?
                """, line);
        assertThatThrownBy(() -> settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 4, "rc-again"))
                .isInstanceOf(SettlementReceiptService.InvalidTransition.class);
        jdbc.update("UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '24 hours' WHERE id = ?", line);
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.ASK_FORGIVE, 4, "rc-again")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.UPDATED);
        var forgiven = settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.FORGIVE, 5, "rc-forgive");
        assertThat(forgiven.line().status()).isEqualTo("forgiven");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, other))
                .isEqualTo("sent");
        assertThat(settlementReceipts.history(payerJwt, host, null).lines()).hasSize(3);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("receiptpayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(50);
        assertThat(settlementReceipts.transition(hostJwt, other, SettlementReceiptService.Action.CONFIRM, 0, "rc-confirm")
                .line().status()).isEqualTo("paid");
        debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("receiptpayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'settlement.line.changed.v1'
                """, Integer.class, line.toString())).isEqualTo(6);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", payer);
        assertThat(settlementReceipts.history(payerJwt, host, null).authenticated()).isFalse();
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 6, "rc-revoked")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.UNAUTHORIZED);
    }

    @Test
    void receiptActionsSerializeConflictingRevisions() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'receiptduelhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcduelpayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Receipt race', 'T', 'R4D3QA')", table);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "unpaid");
        Jwt payerJwt = decoder.decode(loginAs("rcduelpayer").getValue());
        Jwt hostJwt = decoder.decode(loginAs("receiptduelhost").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 0, "rc-race-payer").outcome().name();
                } catch (SettlementReceiptService.StaleRevision exception) {
                    return "STALE";
                }
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.FORGIVE, 0, "rc-race-host").outcome().name();
                } catch (SettlementReceiptService.StaleRevision exception) {
                    return "STALE";
                }
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPDATED", "STALE");
            assertThat(jdbc.queryForObject("SELECT revision FROM settlement_lines WHERE id = ?", Integer.class, line))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'settlement.line.changed.v1'
                    """, Integer.class, line.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void gatedGroupHttpAppliesActionsAndPreservesHistory() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupapipeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupapiother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Group API', 'T', 'G6A2QA')", table);
        UUID owing = insertDebtReceipt(table, host, peer, host, 300, "unpaid");
        UUID offset = insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Cookie hostCookie = loginAs("groupapihost");
        Cookie peerCookie = loginAs("groupapipeer");
        Cookie outsiderCookie = loginAs("groupapiother");
        Csrf csrf = csrf();
        MvcResult listed = mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(listed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode group = json.readTree(listed.getResponse().getContentAsString()).get(0);
        assertThat(OpenApiSchemas.load("SettlementGroup").validate(group)).isEmpty();
        assertThat(group.path("otherUserId").asText()).isEqualTo(host.toString());
        assertThat(group.path("netCents").asLong()).isEqualTo(-200);
        assertThat(group.path("lines").size()).isEqualTo(2);
        String firstRevision = group.path("revision").asText();
        assertThat(json.readTree(mvc.perform(get("/people/settle-groups").cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        mvc.perform(get("/people/settle-groups").cookie(peerCookie).param("includeHistory", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/people/settle-groups").cookie(peerCookie).param("includeHistory", "true", "false"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/people/settle-groups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/people/" + host + "/settle").cookie(peerCookie)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "gp-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"sent\",\"expectedRevision\":\"" + firstRevision + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/people/" + host + "/settle").cookie(peerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "gp-invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"sent\",\"expectedRevision\":\"\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(groupHttpAction(hostCookie, csrf, peer, "sent", firstRevision, "gp-forbidden")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(groupHttpAction(outsiderCookie, csrf, host, "sent", firstRevision, "gp-outside")
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult sent = groupHttpAction(peerCookie, csrf, host, "sent", firstRevision, "gp-sent");
        assertThat(sent.getResponse().getStatus()).isEqualTo(204);
        assertThat(sent.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'sent'",
                Integer.class, owing, offset)).isEqualTo(2);
        assertThat(groupHttpAction(peerCookie, csrf, host, "sent", firstRevision, "gp-sent")
                .getResponse().getStatus()).isEqualTo(204);
        MvcResult stale = groupHttpAction(hostCookie, csrf, peer, "unpaid", firstRevision, "gp-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_revision");
        String sentRevision = groupRevision(hostCookie);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "unpaid", sentRevision, "gp-unpaid").getResponse().getStatus())
                .isEqualTo(204);
        String unpaidRevision = groupRevision(peerCookie);
        assertThat(groupHttpAction(peerCookie, csrf, host, "ask", unpaidRevision, "gp-ask").getResponse().getStatus())
                .isEqualTo(204);
        String askRevision = groupRevision(hostCookie);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "decline", askRevision, "gp-decline").getResponse().getStatus())
                .isEqualTo(204);
        String declinedRevision = groupRevision(peerCookie);
        MvcResult cooldown = groupHttpAction(peerCookie, csrf, host, "ask", declinedRevision, "gp-cooldown");
        assertThat(cooldown.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(cooldown.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("invalid_transition");
        assertThat(groupHttpAction(hostCookie, csrf, peer, "forgiven", declinedRevision, "gp-forgiven")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        JsonNode history = json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie)
                        .param("includeHistory", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(history.path("netCents").asLong()).isZero();
        assertThat(history.path("lines").size()).isEqualTo(2);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "forgiven", declinedRevision, "gp-forgiven")
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void gatedGroupHttpHandlesZeroAndUnrepresentableNets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupedgehost', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'groupedgepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Group edge', 'T', 'G6A3QA')", table);
        insertDebtReceipt(table, host, peer, host, 100, "unpaid");
        insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Cookie hostCookie = loginAs("groupedgehost");
        Cookie peerCookie = loginAs("groupedgepeer");
        Csrf csrf = csrf();
        String revision = groupRevision(peerCookie);
        assertThat(groupHttpAction(peerCookie, csrf, host, "paid", revision, "gp-zero")
                .getResponse().getStatus()).isEqualTo(204);
        insertDebtReceipt(table, host, peer, host, Long.MAX_VALUE, "unpaid");
        insertDebtReceipt(table, host, peer, host, 1, "unpaid");
        JsonNode minimum = json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(minimum.path("netCents").longValue()).isEqualTo(Long.MIN_VALUE);
        MvcResult overflow = mvc.perform(get("/people/settle-groups").cookie(hostCookie))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(overflow.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("group_net_out_of_range");
        assertThat(overflow.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        MvcResult counterparties = mvc.perform(get("/people/settlement-counterparties").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(counterparties.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode counterpartyIds = json.readTree(counterparties.getResponse().getContentAsString());
        assertThat(counterpartyIds.size()).isEqualTo(1);
        assertThat(counterpartyIds.get(0).asText()).isEqualTo(peer.toString());
        UUID reviewRevision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_pair_reviews (user_id, other_user_id, revision)
                VALUES (?, ?, ?)
                """, host, peer, reviewRevision);
        JsonNode pending = pendingReviews(hostCookie);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).path("otherUserId").asText()).isEqualTo(peer.toString());
        assertThat(pending.get(0).path("pendingReviewRevision").asText()).isEqualTo(reviewRevision.toString());
        assertThat(pendingReviews(peerCookie)).isEmpty();
        assertThat(json.readTree(mvc.perform(get("/users/" + peer + "/settlements").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size()).isEqualTo(4);
    }

    private MvcResult groupHttpAction(Cookie cookie, Csrf csrf, UUID other, String step, String revision, String key)
            throws Exception {
        return mvc.perform(post("/people/" + other + "/settle").cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"" + step + "\",\"expectedRevision\":\"" + revision + "\"}")).andReturn();
    }

    private String groupRevision(Cookie cookie) throws Exception {
        return json.readTree(mvc.perform(get("/people/settle-groups").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).path("revision").asText();
    }

    @Test
    void receiptGroupsApplyNetActionsAtomicallyAcrossOffsets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupcreditor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupdebtor', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Group one', 'T', 'G4A3QA'), (?, 'Group two', 'T', 'G4A4QA')
                """, table, secondTable);
        UUID main = insertDebtReceipt(table, host, peer, host, 1500, "unpaid");
        UUID offset = insertDebtReceipt(secondTable, host, host, peer, 500, "unpaid");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", secondTable);
        Jwt hostJwt = decoder.decode(loginAs("groupcreditor").getValue());
        Jwt peerJwt = decoder.decode(loginAs("groupdebtor").getValue());
        var hostGroup = settlementGroups.read(hostJwt, false).groups().getFirst();
        var peerGroup = settlementGroups.read(peerJwt, false).groups().getFirst();
        assertThat(hostGroup.netCents()).isEqualTo(java.math.BigInteger.valueOf(1000));
        assertThat(peerGroup.netCents()).isEqualTo(java.math.BigInteger.valueOf(-1000));
        assertThat(hostGroup.revision()).isEqualTo(peerGroup.revision());
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.SENT,
                hostGroup.revision(), "group-forbidden")).isEqualTo(SettlementGroupService.Outcome.FORBIDDEN);
        settlementReceipts.transition(hostJwt, offset, SettlementReceiptService.Action.SENT, 0, "group-offset-sent");
        assertThatThrownBy(() -> settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT,
                peerGroup.revision(), "group-stale")).isInstanceOf(SettlementGroupService.StaleRevision.class);
        String revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-offset-ask"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT status = 'sent' AND forgive_requested_at IS NOT NULL FROM settlement_lines WHERE id = ?
                """, Boolean.class, offset)).isTrue();
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.DECLINE, revision, "group-offset-decline"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, offset))
                .isEqualTo("sent");
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT, revision, "group-sent"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'sent'
                """, Integer.class, main, offset)).isEqualTo(2);
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT, revision, "group-sent"))
                .isEqualTo(SettlementGroupService.Outcome.REPLAYED);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        String sentRevision = revision;
        assertThatThrownBy(() -> settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                sentRevision, "group-cant-forgive")).isInstanceOf(SettlementGroupService.InvalidTransition.class);
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.UNPAID, revision, "group-dispute"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-ask"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'forgive_asked'
                    AND forgive_requested_at IS NOT NULL
                """, Integer.class, main, offset)).isEqualTo(2);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.DECLINE, revision, "group-decline"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        String declinedRevision = revision;
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '23 hours 59 minutes'
                WHERE id IN (?, ?)
                """, main, offset);
        assertThatThrownBy(() -> settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK,
                declinedRevision, "group-again")).isInstanceOf(SettlementGroupService.InvalidTransition.class);
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '24 hours'
                WHERE id IN (?, ?)
                """, main, offset);
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-again"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                revision, "group-forgiven")).isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(settlementGroups.read(hostJwt, false).groups()).isEmpty();
        assertThat(settlementGroups.read(hostJwt, true).groups().getFirst().lines()).hasSize(2)
                .allSatisfy(line -> assertThat(line.status()).isEqualTo("forgiven"));
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                revision, "group-forgiven")).isEqualTo(SettlementGroupService.Outcome.REPLAYED);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("groupdebtor")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
    }

    @Test
    void receiptGroupsClearZeroNetAndRollBackFailedBatch() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupzerohost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupzeropeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Zero group', 'T', 'G4Z3QA')", table);
        UUID first = insertDebtReceipt(table, host, peer, host, 100, "unpaid");
        UUID second = insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Jwt jwt = decoder.decode(loginAs("groupzerohost").getValue());
        var group = settlementGroups.read(jwt, false).groups().getFirst();
        assertThat(group.netCents()).isEqualTo(java.math.BigInteger.ZERO);
        assertThat(settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, group.revision(), "zero-group"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'paid'",
                Integer.class, first, second)).isEqualTo(2);
        UUID later = insertDebtReceipt(table, host, peer, host, 150, "unpaid");
        UUID laterOffset = insertDebtReceipt(table, host, host, peer, 50, "unpaid");
        jdbc.update("UPDATE settlement_lines SET revision = 2147483647 WHERE id = ?", laterOffset);
        String revision = settlementGroups.read(jwt, false).groups().getFirst().revision();
        assertThatThrownBy(() -> settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, revision, "group-rollback"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'unpaid'",
                Integer.class, later, laterOffset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.group.changed.v1'
                  AND payload->>'userId' = ?
                """, Integer.class, host.toString())).isEqualTo(1);
        jdbc.update("UPDATE settlement_lines SET revision = 0 WHERE id = ?", laterOffset);
        String fresh = settlementGroups.read(jwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, fresh, "group-rollback"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
    }

    @Test
    void groupActionRejectsRevisionChangedByConcurrentSettlement() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupnetrace', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupnetpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Net race', 'T', 'G4R3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID receipt = insertDebtReceipt(table, host, peer, host, 50, "unpaid");
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        Jwt hostJwt = decoder.decode(loginAs("groupnetrace").getValue());
        Jwt peerJwt = decoder.decode(loginAs("groupnetpeer").getValue());
        settlementReceipts.transition(peerJwt, receipt, SettlementReceiptService.Action.ASK_FORGIVE, 0, "net-ask");
        settlementReceipts.transition(peerJwt, receipt, SettlementReceiptService.Action.SENT, 1, "net-sent");
        String oldRevision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                        (rs, row) -> rs.getObject("id", UUID.class), host, peer);
                assertThat(pointsSettlement.settle(hostJwt, table, "net-settlement").outcome())
                        .isEqualTo(TablePointsSettlementService.Outcome.CREATED);
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var action = executor.submit(() -> settlementGroups.transition(hostJwt, peer,
                    SettlementGroupService.Action.PAID, oldRevision, "net-group-action"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> action.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SettlementGroupService.StaleRevision.class);
            var state = settlementReceipts.history(peerJwt, host, table).lines();
            assertThat(state).hasSize(2).allSatisfy(line -> assertThat(line.status()).isEqualTo("unpaid"));
            assertThat(state.stream().filter(line -> line.id().equals(receipt)).findFirst().orElseThrow())
                    .satisfies(line -> {
                        assertThat(line.forgiveRequestedAt()).isNull();
                        assertThat(line.revision()).isEqualTo(3);
                    });
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                    AND payload->>'userId' = ? AND payload->>'otherUserId' = ?
                    """, Integer.class, host.toString(), peer.toString())).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void u2PickResolutionPostsZeroSumAndHonorsRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID winner1 = UUID.randomUUID();
        UUID winner2 = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resolvehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin1', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin2', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveloser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, winner1, hash, winner2, hash, loser, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution', 'T', 'Y8R5QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, winner1, table, winner2, table, loser);
        UUID bet = insertDebtBet(table, winner1, host, "PENDING", false, true, false, 101, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants', remainder_rule = 'host', remainder_host_id = ? WHERE id = ?",
                host, bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", bet, winner2, bet, loser);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No')",
                bet, winner2, bet, loser);
        Cookie hostCookie = loginAs("resolvehost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt winnerJwt = decoder.decode(loginAs("resolvewin1").getValue());
        Jwt outsiderJwt = decoder.decode(loginAs("resolveoutside").getValue());
        assertThat(u2PickResolution.resolve(winnerJwt, bet, "Yes", "resolve-winner"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(outsiderJwt, bet, "Yes", "resolve-outsider"))
                .isEqualTo(U2PickResolutionService.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Typo", "resolve-key"))
                .isInstanceOf(U2PickResolutionService.InvalidChoice.class);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.REPLAYED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "No", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.CONFLICT);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-again"))
                .isInstanceOf(U2PickResolutionService.Closed.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, host)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, winner1)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, loser)).isEqualTo(-101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'",
                Integer.class, bet.toString())).isEqualTo(1);
        JsonNode view = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(view)).isEmpty();
        assertThat(view.path("status").asText()).isEqualTo("SETTLED");
        assertThat(view.path("calledBy").asText()).isEqualTo(host.toString());
        assertThat(view.path("winningChoice").asText()).isEqualTo("Yes");
        assertThat(view.path("pointsSettled").asBoolean()).isFalse();
    }

    @Test
    void u2PickResolutionHandlesWashesFourFunAndFrozenFallback() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resultfunhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfunuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfrozen', 1, ?, 'PLAYER', 'FROZEN')
                """, host, hash, player, hash, frozen, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Fun result', 'T', 'Y8R6QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, player, table, frozen);
        UUID fun = insertDebtBet(table, host, host, "PENDING", true, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants' WHERE id = ?", fun);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", fun, player, fun, frozen);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", fun, player);
        Cookie hostCookie = loginAs("resultfunhost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt playerJwt = decoder.decode(loginAs("resultfunuser").getValue());
        assertThat(u2PickResolution.resolve(playerJwt, fun, "Yes", "fun-nonhost"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(hostJwt, fun, "Yes", "fun-host"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, fun)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, fun)).isZero();
        UUID washed = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'me' WHERE id = ?", washed);
        assertThat(u2PickResolution.resolve(playerJwt, washed, null, "null-nonproposer"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        Csrf csrf = csrf();
        mvc.perform(post("/bets/" + washed + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "null-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, washed)).isEqualTo("WASHED");
        assertThat(jdbc.queryForObject("SELECT wash_reason FROM bets WHERE id = ?", String.class, washed))
                .isEqualTo("Outcome fell outside the available choices");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, washed)).isZero();
        UUID sports = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", sports);
        assertThat(u2PickResolution.resolve(hostJwt, sports, "Yes", "sports-manual"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
    }

    @Test
    void u2PickResolutionRejectsPreexistingPendingLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resbadhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resbadplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bad ledger', 'T', 'Y8S4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, -100);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resbadhost").getValue());
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-existing"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already has ledger entries");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                """, Integer.class, bet.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
    }

    @Test
    void u2PickResolutionRetriesWhenMembershipChangesWhileAcquiringLocks() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID joining = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resraceuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resracejoin', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, joining, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution race', 'T', 'Y8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resracehost").getValue());
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, joining);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, joining);
                inserted.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var resolution = executor.submit(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> resolution.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(U2PickResolutionService.MembersChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
            assertThat(u2PickResolution.resolve(jwt, bet, "Yes", "res-race"))
                    .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousU2PickResolutionsPostOnlyOneLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resduelhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resdueluser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution duel', 'T', 'Y8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, host, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'anyone' WHERE id = ?", bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", bet, player);
        Jwt hostJwt = decoder.decode(loginAs("resduelhost").getValue());
        Jwt playerJwt = decoder.decode(loginAs("resdueluser").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(hostJwt, bet, "Yes", "res-duel-host").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(playerJwt, bet, "No", "res-duel-player").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESOLVED", "CLOSED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                    """, Integer.class, bet.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void u2PickResolutionFailsClosedWhenCopiesNeedPropagation() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID copyTable = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'rescopyhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Source', 'T', 'Y8R9QA'), (?, 'Copy', 'T', 'Y8S2QA')
                """, table, copyTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, host, copyTable, host);
        UUID source = insertDebtBet(table, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", source);
        UUID copy = insertDebtBet(copyTable, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET source_bet_id = ?, source_table_id = ? WHERE id = ?", source, table, copy);
        Cookie cookie = loginAs("rescopyhost");
        Jwt jwt = decoder.decode(cookie.getValue());
        assertThat(u2PickResolution.resolve(jwt, copy, "Yes", "res-copy"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, source, "Yes", "res-source"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("propagation");
        Csrf csrf = csrf();
        MvcResult blocked = mvc.perform(post("/bets/" + source + "/settle").cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "res-http-source").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("quick_pick_propagation_unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, source)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, source)).isZero();
    }

    @Test
    void u2PickProposalPersistsNormalizedDefinitionAndSafeRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'prophost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Proposal', 'T', 'T8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie cookie = loginAs("propplayer");
        Jwt jwt = decoder.decode(cookie.getValue());
        var config = json.createObjectNode().put("z", 2).put("a", "metadata").put("question", "Conflicting?");
        config.putArray("options").add("Wrong").add("Values");
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition(" Question? ", List.of(" Yes ", "No")),
                config, 100, 45, false, null);
        var created = u2PickProposals.create(jwt, table, proposal, "proposal-key");
        assertThat(created.outcome()).isEqualTo(U2PickProposalService.Outcome.CREATED);
        UUID bet = created.betId();
        assertThat(bet).isNotNull();
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (locks_at - created_at)) FROM bets WHERE id = ?",
                Long.class, bet)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.created.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);
        JsonNode view = json.readTree(mvc.perform(get("/bets/" + bet).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(view)).isEmpty();
        assertThat(view.path("question").asText()).isEqualTo("Question?");
        assertThat(view.path("choices").get(0).asText()).isEqualTo("Yes");
        assertThat(view.path("config").path("question").asText()).isEqualTo("Question?");
        assertThat(view.path("config").path("options")).isEqualTo(view.path("choices"));
        assertThat(view.path("config").path("a").asText()).isEqualTo("metadata");
        assertThat(view.path("settledBy").asText()).isEqualTo("hosts");
        assertThat(view.path("myParticipation").asText()).isEqualTo("not_joined");
        var reordered = new U2PickProposalService.Proposal(new U2PickDefinition("Question?", List.of("Yes", "No")),
                json.createObjectNode().put("a", "metadata").put("z", 2), 100, 45, false,
                U2PickSettler.Rule.HOSTS);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key"))
                .isEqualTo(new U2PickProposalService.Result(U2PickProposalService.Outcome.REPLAYED, bet));
        var changed = new U2PickProposalService.Proposal(reordered.definition(), reordered.config(), 100, 60,
                false, U2PickSettler.Rule.HOSTS);
        assertThat(u2PickProposals.create(jwt, table, changed, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.created.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);
        jdbc.update("UPDATE tables SET u2pick_enabled = false, hosts_only_bets = true WHERE id = ?", table);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.REPLAYED);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-denied").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.NOT_FOUND);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.UNAUTHORIZED);
    }

    @Test
    void u2PickProposalChecksCapPermissionsAndInput() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'propcaphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propcapuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, hosts_only_bets)
                VALUES (?, 'Proposal cap', 'T', 'T8R9QA', true)
                """, table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 99)", player);
        Jwt jwt = decoder.decode(loginAs("propcapuser").getValue());
        U2PickDefinition definition = new U2PickDefinition("Question?", List.of("Yes", "No"));
        var proposal = new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 30,
                false, U2PickSettler.Rule.ME);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-cap").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE tables SET hosts_only_bets = false WHERE id = ?", table);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, proposal, "proposal-cap"))
                .isInstanceOf(U2PickProposalService.DebtCapExceeded.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE table_id = ?", Integer.class, table)).isZero();
        var fun = new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 600,
                true, U2PickSettler.Rule.ANYONE);
        assertThat(u2PickProposals.create(jwt, table, fun, "proposal-fun").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", player);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-cap").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
        assertThatThrownBy(() -> new U2PickProposalService.Proposal(definition, json.createObjectNode(), 9, 30,
                false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 601,
                false, null)).isInstanceOf(IllegalArgumentException.class);
        var invalidConfig = new U2PickProposalService.Proposal(definition, json.createObjectNode().put("bad", true),
                100, 30, false, null);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, invalidConfig, "proposal-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        var invalidText = new U2PickProposalService.Proposal(definition,
                json.createObjectNode().put("note", "\u0000"), 100, 30, false, null);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, invalidText, "proposal-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-invalid").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
    }

    @Test
    void u2PickProposalRechecksTableSettingsAfterWaiting() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'propwaithost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propwaituser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Proposal wait', 'T', 'T8S2QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Jwt jwt = decoder.decode(loginAs("propwaituser").getValue());
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition("Question?", List.of("Yes", "No")),
                json.createObjectNode(), 100, 30, false, U2PickSettler.Rule.HOSTS);
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, host);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE tables SET u2pick_enabled = false WHERE id = ?", table);
                changed.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var request = executor.submit(() -> u2PickProposals.create(jwt, table, proposal, "proposal-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT hosts_only_bets, u2pick_enabled FROM tables%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(request.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE table_id = ?", Integer.class, table)).isZero();
            jdbc.update("UPDATE tables SET u2pick_enabled = true WHERE id = ?", table);
            assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-race").outcome())
                    .isEqualTo(U2PickProposalService.Outcome.CREATED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betPickPreservesParticipationAndRetryPrivacy() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Pick', 'T', 'T8R5QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '10 minutes' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("pickplayer").getValue());
        assertThat(betPicks.change(jwt, bet, null, "pick-clear-before")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Other", "pick-invalid"))
                .isInstanceOf(BetPickService.InvalidChoice.class);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.UPDATED);
        Long order = jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                String.class, bet, player)).isEqualTo("No");
        assertThat(betPicks.change(jwt, bet, "No", "pick-invalid")).isEqualTo(BetPickService.Outcome.CONFLICT);
        assertThat(betPicks.change(jwt, bet, null, "pick-clear")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player)).isEqualTo(order);
        List<String> payloads = jdbc.queryForList("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.pick_count.changed.v1'
                """, String.class, bet.toString());
        assertThat(payloads).hasSize(3);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.size()).isEqualTo(2);
            assertThat(event.path("betId").asText()).isEqualTo(bet.toString());
            assertThat(event.path("tableId").asText()).isEqualTo(table.toString());
        }
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "No", "pick-after-lock"))
                .isInstanceOf(BetPickService.Closed.class);
        jdbc.update("UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UNAUTHORIZED);
    }

    @Test
    void betPicksUseSharedCapAndExemptFourFun() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickcaphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickcapuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick cap', 'T', 'T8R6QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 99)", player);
        insertDebtBet(table, player, host, "PENDING", false, true, false, 50, 0);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 50, 0);
        UUID fun = insertDebtBet(table, player, host, "ACTIVE", true, false, false, 50, 0);
        jdbc.update("""
                UPDATE bets SET locks_at = now() + interval '10 minutes',
                    mode = 'either_or', game_id = 'game-1' WHERE id IN (?, ?)
                """, bet, fun);
        Jwt jwt = decoder.decode(loginAs("pickcapuser").getValue());
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Yes", "pick-cap"))
                .isInstanceOf(BetPickService.DebtCapExceeded.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, fun, "Yes", "pick-fun")).isEqualTo(BetPickService.Outcome.UPDATED);
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-cap")).isEqualTo(BetPickService.Outcome.UPDATED);
    }

    @Test
    void betPickRechecksDeadlineAfterWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickwaithost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickwaituser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick wait', 'T', 'T8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("pickwaituser").getValue());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                held.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '4 seconds' WHERE id = ?", bet);
            var pick = executor.submit(() -> betPicks.change(jwt, bet, "Yes", "pick-wait"));
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < timeout) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < locks_at FROM bets WHERE id = ?",
                    Boolean.class, bet)).isTrue();
            boolean due = false;
            while (!due && System.nanoTime() < timeout) {
                due = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT clock_timestamp() >= locks_at FROM bets WHERE id = ?", Boolean.class, bet));
                if (!due) Thread.sleep(25);
            }
            assertThat(due).isTrue();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> pick.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BetPickService.Closed.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '1 minute' WHERE id = ?", bet);
            assertThat(betPicks.change(jwt, bet, "Yes", "pick-wait")).isEqualTo(BetPickService.Outcome.UPDATED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betLockSnapshotsAndRechecksEligibilityWithoutLosingParticipation() {
        UUID host = UUID.randomUUID();
        UUID exact = UUID.randomUUID();
        UUID over = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID limited = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockexact', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockover', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'locklimited', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, exact, hash, over, hash, frozen, hash, limited, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Lock', 'T', 'V8R5QA', 'host')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_bet) VALUES
                (?, ?, 'HOST', true), (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', true),
                (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', false)
                """, table, host, table, exact, table, over, table, frozen, table, limited);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100), (?, 99)", exact, over);
        UUID bet = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?)
                """, bet, over, bet, frozen, bet, limited);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No'), (?, ?, 'No'), (?, ?, 'No')
                """, bet, over, bet, frozen, bet, limited);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForList("SELECT user_id FROM picks WHERE bet_id = ?", UUID.class, bet))
                .containsExactly(exact);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, bet))
                .isEqualTo(host);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.ALREADY_LOCKED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);

        UUID fun = insertDebtBet(table, over, host, "ACTIVE", true, true, false, 100, 0);
        assertThat(betLocks.lock(fun)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, fun)).isEqualTo(1);
        UUID future = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '1 minute' WHERE id = ?", future);
        assertThat(betLocks.lock(future)).isEqualTo(BetLockService.Outcome.NOT_DUE);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, future)).isEqualTo("ACTIVE");
    }

    @Test
    void betLockRollsBackWhenHostRemainderHasNoHost() {
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'locknohost', 1, ?, 'PLAYER', 'ACTIVE')
                """, player, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'No host', 'T', 'V8R6QA', 'host')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0)", player);
        UUID bet = insertDebtBet(table, player, player, "ACTIVE", false, true, false, 100, 0);
        assertThatThrownBy(() -> betLocks.lock(bet)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'",
                Integer.class, bet.toString())).isZero();
    }

    @Test
    void simultaneousBetLocksSerializeSharedDebtExposure() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID firstTable = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockraceuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES
                (?, 'First', 'T', 'V8R7QA', 'earliest'), (?, 'Second', 'T', 'V8R8QA', 'earliest')
                """, firstTable, secondTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, firstTable, host, firstTable, player, secondTable, host, secondTable, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", player);
        UUID first = insertDebtBet(firstTable, player, host, "ACTIVE", false, true, false, 100, 0);
        UUID second = insertDebtBet(secondTable, player, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", second);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(first);
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(second);
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betLockRetriesWhenPickAppearsWhileWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'locknewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'locknewuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Race', 'T', 'V8R9QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, player);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
                jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, player);
                inserted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var lock = executor.submit(() -> betLocks.lock(bet));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT remainder_rule FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> lock.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BetLockService.ParticipantsChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
            assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtReadUsesExactAggregateArithmeticAndFailsOnUnrepresentableDebt() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtexact', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'debtexactpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Exact', 'T', 'D6R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, 1);
        Cookie session = loginAs("debtexact");
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -100);
        Jwt jwt = decoder.decode(session.getValue());
        assertThatThrownBy(() -> settingsReads.debt(jwt)).isInstanceOf(ArithmeticException.class);
    }

    private UUID insertDebtBet(UUID table, UUID user, UUID other, String status, boolean forFun,
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

    private void appendDebtDelta(UUID bet, UUID table, UUID user, UUID other, long userDelta) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> jdbc.update("""
                INSERT INTO ledger_entries (id, bet_id, table_id, user_id, delta_cents)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), bet, table, user, userDelta,
                UUID.randomUUID(), bet, table, other, -userDelta));
    }

    private UUID insertDebtReceipt(
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

    @Test
    void debtCapUpdatesRequireZeroDebtAndAreRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capwriter', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capwriterpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Cap', 'T', 'D8R3QA')", table);
        Cookie session = loginAs("capwriter");
        Csrf csrf = csrf();
        mvc.perform(put("/me/debt-cap").cookie(csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "cap-anonymous")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/me/debt-cap").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/debt-cap").cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "cap-first")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isForbidden());
        for (String invalid : List.of("{}", "{\"capCents\":-100}", "{\"capCents\":150}", "{\"capCents\":1.0}",
                "{\"capCents\":2147483648}", "{\"capCents\":\"100\"}", "{\"capCents\":0,\"extra\":true}")) {
            mvc.perform(debtCapPut(session, csrf, "bad-cap", invalid)).andExpect(status().isBadRequest());
        }
        MvcResult saved = mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2147483600);
        UUID receipt = insertDebtReceipt(table, other, user, other, 1, "sent");
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent());
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":0}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-same", "{\"capCents\":2147483600}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        jdbc.update("UPDATE settlement_lines SET status = 'paid' WHERE id = ?", receipt);
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT cap_cents IS NULL FROM debt_caps WHERE user_id = ?", Boolean.class, user))
                .isTrue();
        mvc.perform(debtCapPut(session, csrf, "cap-zero", "{\"capCents\":0}")).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "cap-zero", "{\"friends\":\"open\",\"following\":\"request\"}"))
                .andExpect(status().isConflict());
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        mvc.perform(debtCapPut(session, csrf, "cap-huge", "{\"capCents\":null}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isZero();
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCaps.replace(validated, "cap-first", 2147483600)).isEqualTo(DebtCapService.Outcome.UNAUTHORIZED);
    }

    @Test
    void debtCapUpdateSerializesWithDebtChangingUserLocks() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'caprace', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capracepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'D8R4QA')", table);
        Jwt jwt = decoder.decode(loginAs("caprace").getValue());
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user);
                insertDebtReceipt(table, other, user, other, 100, "unpaid");
                changed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var update = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateSerializesWithInvitationAcceptance() throws Exception {
        UUID user = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capinvite', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capinvitehost', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Invite', 'T', 'D8R5QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", user, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, user);
        insertDebtBet(table, user, host, "PENDING", false, true, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("capinvite").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var accept = executor.submit(() -> invitationResponses.respond(jwt, invite, true, "invite-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            var cap = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-invite-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(accept.get(10, TimeUnit.SECONDS)).isEqualTo(InvitationResponseService.Outcome.UPDATED);
            assertThatThrownBy(() -> cap.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateRollsBackIfSessionExpiresWhileWaiting() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'capexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", user);
        Jwt validated = decoder.decode(loginAs("capexpiry").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT user_id FROM debt_caps WHERE user_id = ? FOR UPDATE", UUID.class, user);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> debtCaps.replace(expiring, "cap-expiry", 200));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO debt_caps (user_id, cap_cents)%'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void settingsReadsReturnOwnValuesAndRecheckCurrentSession() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'settingone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingtwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        mvc.perform(get("/me/debt-cap")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("settingone");
        Cookie otherSession = loginAs("settingtwo");

        Csrf csrf = csrf();
        mvc.perform(get("/me/notification-preferences").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/notifications").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(post("/notifications/read-all").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.DELETE, "/notifications").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());

        MvcResult emptyDebt = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(emptyDebt.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(emptyDebt.getResponse().getContentAsString()).path("debtCents").asLong()).isZero();
        MvcResult defaultCap = mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(defaultCap.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode cap = json.readTree(defaultCap.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.has("capCents")).isTrue();
        assertThat(cap.path("capCents").isNull()).isTrue();
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0), (?, 321)", user, other);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").asInt()).isZero();
        JsonNode otherCap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherCap.path("capCents").asInt()).isEqualTo(321);
        jdbc.update("UPDATE debt_caps SET cap_cents = 2147483647 WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.path("capCents").asLong()).isEqualTo(2147483647L);
        jdbc.update("UPDATE debt_caps SET cap_cents = NULL WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").isNull()).isTrue();

        Jwt validatedBeforeFreeze = decoder.decode(session.getValue());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        assertThat(settingsReads.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        assertThat(settingsReads.debt(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt-cap").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(settingsReads.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void userSearchFiltersVisibilityAndPagesByUsername() throws Exception {
        UUID viewer = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'find_viewer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'findx00', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, UUID.randomUUID(), hash);
        List<UUID> users = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            UUID id = UUID.randomUUID();
            users.add(id);
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 2, ?, 'PLAYER', 'ACTIVE')
                    """, id, "find_" + String.format("%02d", i), hash);
        }
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", users.get(10));
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, users.get(22));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, users.get(20), users.get(21), viewer);

        mvc.perform(get("/users").param("query", "find_")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("find_viewer");
        MvcResult response = mvc.perform(get("/users").cookie(session).param("query", "  FIND_  "))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode page = json.readTree(response.getResponse().getContentAsString());
        assertThat(page.path("items").size()).isEqualTo(50);
        assertThat(page.path("items").get(0).path("username").asText()).isEqualTo("find_00");
        assertThat(page.path("items").toString()).contains(users.get(10).toString())
                .doesNotContain(viewer.toString(), users.get(20).toString(), users.get(21).toString(),
                        users.get(22).toString(), "findx00", "status");
        for (JsonNode user : page.path("items")) {
            assertThat(OpenApiSchemas.load("User").validate(user)).isEmpty();
        }
        JsonNode substring = json.readTree(mvc.perform(get("/users").cookie(session).param("query", "ND_10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(substring.path("items").size()).isEqualTo(1);
        assertThat(substring.path("items").get(0).path("id").asText()).isEqualTo(users.get(10).toString());
        JsonNode empty = json.readTree(mvc.perform(get("/users").cookie(session).param("query", "no_such_user"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        String cursor = page.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", viewer, users.get(53));
        page = json.readTree(mvc.perform(get("/users").cookie(session)
                        .param("query", "find_").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("username").asText()).isEqualTo("find_54");
        assertThat(page.path("nextCursor").isNull()).isTrue();
        mvc.perform(get("/users").cookie(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find%")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "abcdefghijklmnop")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find_", "other")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find_").param("cursor", "%%%"))
                .andExpect(status().isBadRequest());
        Jwt validatedBeforeRevocation = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
        params.add("query", "find_");
        assertThat(userSearch.search(validatedBeforeRevocation, params).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void userLookupRespectsBlocksAndHistoricalConnections() throws Exception {
        String hash = passwords.encode("a secure test password");
        var ids = new java.util.LinkedHashMap<String, UUID>();
        for (String name : List.of("lkviewer", "lkout", "lkboth", "lkin", "lkshared", "lkreceipt",
                "lkfrozen", "lkdelactive", "lkdelarchive", "lkdelold", "lkdelreceipt", "lkdelunrel",
                "lkdelchat", "lkdelmsg")) {
            UUID id = UUID.randomUUID();
            ids.put(name, id);
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 2, ?, 'PLAYER', 'ACTIVE')
                    """, id, name, hash);
        }
        UUID viewer = ids.get("lkviewer");
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", ids.get("lkfrozen"));
        for (String name : List.of("lkdelactive", "lkdelarchive", "lkdelold", "lkdelreceipt", "lkdelunrel",
                "lkdelchat", "lkdelmsg")) {
            jdbc.update("""
                    UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                        password_hash = NULL WHERE id = ?
                    """, ids.get(name));
        }
        jdbc.update("""
                INSERT INTO blocks (blocker_id, blocked_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, viewer, ids.get("lkout"), viewer, ids.get("lkboth"), ids.get("lkboth"), viewer,
                ids.get("lkin"), viewer, ids.get("lkshared"), viewer, ids.get("lkreceipt"), viewer);
        UUID active = UUID.randomUUID();
        UUID archive = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at)
                VALUES (?, 'Lookup active', 'T', 'WK2AA2', NULL),
                       (?, 'Lookup archive', 'T', 'WK2AA3', now() - interval '1 day'),
                       (?, 'Lookup expired', 'T', 'WK2AA4', now() - interval '91 days')
                """, active, archive, expired);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, active, viewer, active, ids.get("lkshared"), active, ids.get("lkdelactive"));
        jdbc.update("""
                INSERT INTO table_archive_members (table_id, user_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?)
                """, archive, viewer, archive, ids.get("lkdelarchive"),
                expired, viewer, expired, ids.get("lkdelold"));
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlement, expired, viewer);
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, 100), (?, ?, ?, ?, ?, ?, 100)
                """, UUID.randomUUID(), settlement, expired, viewer, viewer, ids.get("lkreceipt"),
                UUID.randomUUID(), settlement, expired, viewer, viewer, ids.get("lkdelreceipt"));
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Test?', '["Yes","No"]'::jsonb, 0, true, now())
                """, bet, active, ids.get("lkdelchat"));
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id, created_at)
                VALUES (?, ?, 'bet', ?, ?, now())
                """, UUID.randomUUID(), active, ids.get("lkdelchat"), bet);
        UUID archiveSettlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)",
                archiveSettlement, archive, ids.get("lkout"));
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, 100)
                """, UUID.randomUUID(), archiveSettlement, archive, ids.get("lkout"), ids.get("lkout"), ids.get("lkdelmsg"));
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, settlement_id, created_at)
                VALUES (?, ?, 'settlement', ?, now())
                """, UUID.randomUUID(), archive, archiveSettlement);

        mvc.perform(get("/users/" + ids.get("lkout"))).andExpect(status().isUnauthorized());
        Cookie session = loginAs("lkviewer");
        for (String name : List.of("lkviewer", "lkout", "lkboth", "lkshared", "lkreceipt", "lkfrozen")) {
            MvcResult response = mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isOk()).andReturn();
            assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            JsonNode identity = json.readTree(response.getResponse().getContentAsString());
            assertThat(OpenApiSchemas.load("User").validate(identity)).isEmpty();
            assertThat(identity.path("username").asText()).isEqualTo(name);
        }
        for (String name : List.of("lkdelactive", "lkdelarchive", "lkdelreceipt", "lkdelchat", "lkdelmsg")) {
            JsonNode identity = json.readTree(mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(OpenApiSchemas.load("DeletedUser").validate(identity)).isEmpty();
            assertThat(identity.path("id").asText()).isEqualTo(ids.get(name).toString());
        }
        String missing = mvc.perform(get("/users/" + UUID.randomUUID()).cookie(session))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        for (String name : List.of("lkin", "lkdelold", "lkdelunrel")) {
            String hidden = mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(hidden).isEqualTo(missing);
        }
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", active, ids.get("lkshared"));
        mvc.perform(get("/users/" + ids.get("lkshared")).cookie(session)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() - interval '91 days' WHERE id = ?", archive);
        mvc.perform(get("/users/" + ids.get("lkdelarchive")).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/users/" + ids.get("lkdelmsg")).cookie(session)).andExpect(status().isNotFound());
        Jwt validatedBeforeRevocation = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(userLookup.get(validatedBeforeRevocation, ids.get("lkout")).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void relationshipReadsKeepBlocksPrivateAndFilterStaleFollows() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID blocker = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'relviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relfriend', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relblocked', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relblocker', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relfollower', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, friend, hash, blocked, hash, blocker, hash, follower, hash);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, viewer, friend, friend, viewer, viewer, blocked, blocked, viewer,
                viewer, blocker, blocker, viewer, follower, viewer);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, blocked, blocker, viewer);

        mvc.perform(get("/me/relationships")).andExpect(status().isUnauthorized());
        Cookie viewerSession = loginAs("relviewer");
        Cookie blockedSession = loginAs("relblocked");
        MvcResult response = mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode relationships = json.readTree(response.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Relationships").validate(relationships)).isEmpty();
        assertThat(relationships.path("followingIds").size()).isEqualTo(1);
        assertThat(relationships.path("followingIds").get(0).asText()).isEqualTo(friend.toString());
        assertThat(relationships.path("followerIds").size()).isEqualTo(2);
        assertThat(relationships.path("followerIds").toString()).contains(friend.toString(), follower.toString());
        assertThat(relationships.path("blockedIds").size()).isEqualTo(1);
        assertThat(relationships.path("blockedIds").get(0).asText()).isEqualTo(blocked.toString());
        assertThat(relationships.toString()).doesNotContain(blocker.toString());

        JsonNode blockedView = json.readTree(mvc.perform(get("/me/relationships").cookie(blockedSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(blockedView.path("blockedIds").isEmpty()).isTrue();
        assertThat(blockedView.path("followingIds").isEmpty()).isTrue();
        assertThat(blockedView.path("followerIds").isEmpty()).isTrue();

        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", follower);
        relationships = json.readTree(mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(relationships.path("followerIds").toString()).contains(follower.toString());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(),
                    username = NULL, hue = NULL, password_hash = NULL WHERE id IN (?, ?)
                """, follower, blocked);
        relationships = json.readTree(mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(relationships.path("followerIds").size()).isEqualTo(1);
        assertThat(relationships.path("followerIds").get(0).asText()).isEqualTo(friend.toString());
        assertThat(relationships.path("blockedIds").isEmpty()).isTrue();
        Jwt validatedBeforeRevocation = decoder.decode(viewerSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(socialReads.relationships(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/relationships").cookie(viewerSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationReadsOnlyReturnVisiblePendingInvitesWithoutTableAccess() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID outgoingBlocked = UUID.randomUUID();
        UUID incomingBlocked = UUID.randomUUID();
        UUID deletedSender = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'invitesender', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteblocked', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteblocker', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'invitedeleted', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteother', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, sender, hash, outgoingBlocked, hash, incomingBlocked, hash,
                deletedSender, hash, other, hash);
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID blockedTable = UUID.randomUUID();
        UUID blockerTable = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID alreadyMember = UUID.randomUUID();
        UUID deletedTable = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Older', 'T', 'P3R5QA'), (?, 'Newer', 'T', 'Q3R5QA'),
                (?, 'Blocked', 'T', 'R3R5QA'), (?, 'Blocker', 'T', 'S3R5QA'),
                (?, 'Archived', 'T', 'T3R5QA'), (?, 'Member', 'T', 'U3R5QA'),
                (?, 'Deleted', 'T', 'V3R5QA'), (?, 'Accepted', 'T', 'W3R5QA')
                """, older, newer, blockedTable, blockerTable, archived, alreadyMember, deletedTable, accepted);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                alreadyMember, viewer);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                older, sender, newer, sender);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", viewer, sender);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, outgoingBlocked, incomingBlocked, viewer);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status, created_at) VALUES
                (?, ?, ?, ?, 'PENDING', now() - interval '1 minute'),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'ACCEPTED', now())
                """, UUID.randomUUID(), older, sender, viewer, UUID.randomUUID(), newer, sender, viewer,
                UUID.randomUUID(), blockedTable, outgoingBlocked, viewer,
                UUID.randomUUID(), blockerTable, incomingBlocked, viewer,
                UUID.randomUUID(), archived, sender, viewer,
                UUID.randomUUID(), alreadyMember, sender, viewer,
                UUID.randomUUID(), deletedTable, deletedSender, viewer,
                UUID.randomUUID(), accepted, sender, viewer);
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(),
                    username = NULL, hue = NULL, password_hash = NULL WHERE id = ?
                """, deletedSender);
        mvc.perform(get("/me/invites")).andExpect(status().isUnauthorized());
        Cookie viewerSession = loginAs("inviteviewer");
        Cookie otherSession = loginAs("inviteother");
        MvcResult response = mvc.perform(get("/me/invites").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode invitations = json.readTree(response.getResponse().getContentAsString());
        assertThat(invitations.size()).isEqualTo(2);
        assertThat(invitations.get(0).path("tableId").asText()).isEqualTo(newer.toString());
        assertThat(invitations.get(1).path("tableId").asText()).isEqualTo(older.toString());
        assertThat(invitations.get(0).path("tableName").asText()).isEqualTo("Newer");
        assertThat(invitations.get(0).path("userId").asText()).isEqualTo(viewer.toString());
        for (JsonNode invitation : invitations) {
            assertThat(OpenApiSchemas.load("TableInvite").validate(invitation)).isEmpty();
            assertThat(invitation.has("quickPickCode")).isFalse();
            assertThat(invitation.has("memberIds")).isFalse();
        }
        mvc.perform(get("/tables/" + newer).cookie(viewerSession)).andExpect(status().isNotFound());
        JsonNode otherInvites = json.readTree(mvc.perform(get("/me/invites").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherInvites.isEmpty()).isTrue();
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", sender);
        invitations = json.readTree(mvc.perform(get("/me/invites").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(invitations.size()).isEqualTo(2);
        Jwt validatedBeforeRevocation = decoder.decode(viewerSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(socialReads.invitations(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/invites").cookie(viewerSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentIdempotencyAndKeyChangesFailSafely() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'prefconcurrent', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("prefconcurrent").getValue());
        var preferences = new InvitePreferencesService.Preferences("open", "request");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "concurrent", preferences);
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "concurrent", preferences);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(InvitePreferencesService.Outcome.UPDATED,
                            InvitePreferencesService.Outcome.REPLAYED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            Jwt expired = Jwt.withTokenValue("expired").header("alg", "HS256")
                    .subject(user.toString()).claim("jti", jwt.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(10))
                    .expiresAt(java.time.Instant.now().minusSeconds(1)).build();
            assertThatThrownBy(() -> invitePreferences.replace(expired, "expired", preferences))
                    .isInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            IdempotencyGuard changedKey = new IdempotencyGuard(jdbc, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=");
            TransactionTemplate tx = new TransactionTemplate(transactions);
            assertThatThrownBy(() -> tx.execute(status -> changedKey.claim(user, "concurrent",
                    "invite-preferences.replace.v1", preferences.friends() + "|" + preferences.following())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invitePreferencesReadAndReplaceArePrivateAndRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteprefs', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'prefspeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Invite', 'T', 'T7R6QA')", table);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invitation, table, other, user);
        Cookie session = loginAs("inviteprefs");
        Cookie otherSession = loginAs("prefspeer");
        Csrf csrf = csrf();
        mvc.perform(get("/me/invite-preferences")).andExpect(status().isUnauthorized());
        MvcResult defaults = mvc.perform(get("/me/invite-preferences").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(defaults.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode state = json.readTree(defaults.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("InvitePreferences").validate(state)).isEmpty();
        assertThat(state.path("friends").asText()).isEqualTo("open");
        assertThat(state.path("following").asText()).isEqualTo("request");
        assertThat(state.size()).isEqualTo(2);
        mvc.perform(put("/me/invite-preferences").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friends\":\"closed\",\"following\":\"open\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/invite-preferences").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "invite-first")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friends\":\"closed\",\"following\":\"open\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(invitePreferencesPut(session, csrf, "bad", "{\"friends\":\"closed\",\"following\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(invitePreferencesPut(session, csrf, "bad", "{\"friends\":\"open\",\"following\":\"open\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                Integer.class, user)).isZero();
        String first = "{\"friends\":\"closed\",\"following\":\"open\"}";
        String second = "{\"friends\":\"request\",\"following\":\"closed\"}";
        MvcResult saved = mvc.perform(invitePreferencesPut(session, csrf, "invite-first", first))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(invitePreferencesPut(session, csrf, "invite-second", second)).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "invite-first",
                "{\"following\":\"open\",\"friends\":\"closed\"}")).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "invite-first", second)).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "invite-first", "{\"capCents\":0}")).andExpect(status().isConflict());
        state = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("InvitePreferences").validate(state)).isEmpty();
        assertThat(state.path("friends").asText()).isEqualTo("request");
        assertThat(state.path("following").asText()).isEqualTo("closed");
        JsonNode otherState = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherState.path("friends").asText()).isEqualTo("open");
        assertThat(otherState.path("following").asText()).isEqualTo("request");
        jdbc.update("INSERT INTO invite_preferences (user_id) VALUES (?)", other);
        JsonNode storedDefaults = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(storedDefaults).isEqualTo(otherState);
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invitation))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> jdbc.update("UPDATE invite_preferences SET friends = 'invalid' WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        Jwt validated = decoder.decode(session.getValue());
        assertThatThrownBy(() -> invitePreferences.replace(validated, "db-invalid",
                new InvitePreferencesService.Preferences("invalid", "open")))
                .isInstanceOf(DataIntegrityViolationException.class);
        Jwt expired = Jwt.withTokenValue("expired").header("alg", "HS256")
                .subject(user.toString()).claim("jti", validated.getId())
                .issuedAt(java.time.Instant.now().minusSeconds(10))
                .expiresAt(java.time.Instant.now().minusSeconds(1)).build();
        assertThatThrownBy(() -> invitePreferences.replace(expired, "expired",
                new InvitePreferencesService.Preferences("open", "open")))
                .isInstanceOf(MutationAuthentication.ExpiredSession.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2);
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        assertThat(invitePreferences.replace(validated, "invite-first",
                new InvitePreferencesService.Preferences("closed", "open")))
                .isEqualTo(InvitePreferencesService.Outcome.UNAUTHORIZED);
        assertThat(settingsReads.invitePreferences(validated).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/invite-preferences").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentInvitePreferenceRequestsWithDifferentPayloadsConflict() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'inviterace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("inviterace").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "same-key", new InvitePreferencesService.Preferences("open", "closed"));
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "same-key", new InvitePreferencesService.Preferences("closed", "open"));
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(InvitePreferencesService.Outcome.UPDATED, InvitePreferencesService.Outcome.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void feedbackSubmissionIsValidatedPrivateAndRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'feedbackwrite', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'feedbackother', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        Cookie session = loginAs("feedbackwrite");
        Cookie otherSession = loginAs("feedbackother");
        Csrf csrf = csrf();
        String first = feedbackBody("gif", "\uFEFF\nFive ideas\u00A0", " \u00A0HTTPS://example.com/gif\uFEFF ");
        mvc.perform(post("/feedback").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/feedback").cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "feedback-first").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isForbidden());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "a".repeat(9), null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "a".repeat(1001), null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid text!", "https://")))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid text!", "https://x/\uFEFFy")))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", feedbackBody("gif", "valid\u0000text!", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", "{\"kind\":\"bug\",\"text\":\"valid idea \\uD800\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "bad", "{\"kind\":\"bug\",\"text\":\"valid text!\",\"link\":null}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isZero();

        MvcResult saved = mvc.perform(feedbackPost(session, csrf, "feedback-first", first))
                .andExpect(status().isCreated()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(saved.getResponse().getContentAsString()).isEmpty();
        mvc.perform(feedbackPost(session, csrf, "feedback-first",
                feedbackBody("gif", "Five ideas", "HTTPS://example.com/gif"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT text FROM feedback WHERE user_id = ?", String.class, user))
                .isEqualTo("Five ideas");
        assertThat(jdbc.queryForObject("SELECT link FROM feedback WHERE user_id = ?", String.class, user))
                .isEqualTo("HTTPS://example.com/gif");
        assertThat(jdbc.queryForObject("SELECT response_status FROM http_idempotency WHERE user_id = ?",
                Integer.class, user)).isEqualTo(201);
        var firstSubmission = new FeedbackService.Submission("gif", "Five ideas", "HTTPS://example.com/gif");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.execute(status -> idempotency.claim(user, "feedback-first",
                "feedback.submit.v1", firstSubmission.canonical())))
                .isInstanceOf(IllegalStateException.class);
        mvc.perform(feedbackPost(session, csrf, "feedback-first", feedbackBody("gif", "other idea!", null)))
                .andExpect(status().isConflict());
        mvc.perform(invitePreferencesPut(session, csrf, "feedback-first", "{\"friends\":\"open\",\"following\":\"request\"}"))
                .andExpect(status().isConflict());
        mvc.perform(feedbackPost(otherSession, csrf, "feedback-first", feedbackBody("bug", "other idea!", null)))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "emoji", feedbackBody("other", "\uD83D\uDE00".repeat(5), " ")))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "max-emoji", feedbackBody("other", "\uD83D\uDE00".repeat(500), null)))
                .andExpect(status().isCreated());
        mvc.perform(feedbackPost(session, csrf, "too-long-emoji",
                feedbackBody("other", "\uD83D\uDE00".repeat(501), null))).andExpect(status().isBadRequest());
        mvc.perform(feedbackPost(session, csrf, "non-ascii-url",
                feedbackBody("other", "valid text!", "https://x/\u0085y"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE user_id = ?", Integer.class, user))
                .isEqualTo(4);

        Jwt validated = decoder.decode(session.getValue());
        assertThatThrownBy(() -> feedbackService.submit(validated, "db-invalid",
                new FeedbackService.Submission("bug", "a", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(4);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(feedbackService.submit(validated, "feedback-first",
                new FeedbackService.Submission("gif", "Five ideas", "HTTPS://example.com/gif")))
                .isEqualTo(FeedbackService.Outcome.UNAUTHORIZED);
        mvc.perform(feedbackPost(session, csrf, "feedback-first", first)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentFeedbackRetriesCreateOneEntry() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'feedbackrace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("feedbackrace").getValue());
        var submission = new FeedbackService.Submission("bug", "A test issue", null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return feedbackService.submit(jwt, "same-key", submission);
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return feedbackService.submit(jwt, "same-key", submission);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(FeedbackService.Outcome.CREATED, FeedbackService.Outcome.REPLAYED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void socialMutationsEnforcePrivateBlocksAndRetrySafety() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID incoming = UUID.randomUUID();
        UUID outgoing = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialwriter', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialpeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialthird', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash, third, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Social', 'T', 'T8S4QA'), (?, 'Other', 'T', 'T8S5QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)
                """, user, other, other, user);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES
                (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, incoming, table, other, user, outgoing, table, user, other,
                unrelated, otherTable, third, user);
        Cookie session = loginAs("socialwriter");
        Cookie otherSession = loginAs("socialpeer");
        Csrf csrf = csrf();
        mvc.perform(post("/blocks/" + other).cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/blocks/" + other).cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "block"))
                .andExpect(status().isForbidden());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "bad-body")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        MvcResult blocked = mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "block"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(blocked.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                    OR (follower_id = ? AND followee_id = ?)
                """, Integer.class, user, other, other, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, incoming))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, outgoing))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, unrelated))
                .isEqualTo("PENDING");
        MvcResult denied = mvc.perform(socialRequest(HttpMethod.POST, "/follows/", user, otherSession, csrf, "follow"))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult absent = mvc.perform(socialRequest(HttpMethod.POST, "/follows/", UUID.randomUUID(),
                otherSession, csrf, "follow")).andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNotFound());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", user, otherSession, csrf, "reverse"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/blocks/", other, session, csrf, "unblock"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "block"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, user, other)).isZero();
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNotFound());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/blocks/", user, otherSession, csrf, "unblock"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", third, session, csrf, "follow"))
                .andExpect(status().isConflict());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "follow"))
                .andExpect(status().isConflict());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", other, session, csrf, "unfollow"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
                Integer.class, user, other)).isZero();
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", user, session, csrf, "self"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "frozen-follow"))
                .andExpect(status().isNoContent());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', username = NULL, hue = NULL,
                    password_hash = NULL, deleted_at = now() WHERE id = ?
                """, other);
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "frozen-follow"))
                .andExpect(status().isNotFound());
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(socialMutations.mutate(validated, third, "anything", SocialMutationService.Operation.FOLLOW))
                .isEqualTo(SocialMutationService.Outcome.UNAUTHORIZED);
    }

    @Test
    void concurrentSocialMutationsUseConsistentPairLocking() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, firstId, hash, secondId, hash);
        Jwt firstJwt = decoder.decode(loginAs("socialracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("socialraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var follow = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(firstJwt, secondId, "follow", SocialMutationService.Operation.FOLLOW);
            });
            var firstBlock = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(firstJwt, secondId, "block", SocialMutationService.Operation.BLOCK);
            });
            var secondBlock = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(secondJwt, firstId, "block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            assertThat(follow.get(10, TimeUnit.SECONDS)).isIn(
                    SocialMutationService.Outcome.UPDATED, SocialMutationService.Outcome.NOT_FOUND);
            assertThat(firstBlock.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(secondBlock.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                        OR (follower_id = ? AND followee_id = ?)
                    """, Integer.class, firstId, secondId, secondId, firstId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM blocks WHERE (blocker_id = ? AND blocked_id = ?)
                        OR (blocker_id = ? AND blocked_id = ?)
                    """, Integer.class, firstId, secondId, secondId, firstId)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockRollsBackAllEffectsIfSessionExpiresWhileWaiting() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialexpire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialexppeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Social', 'T', 'T8S6QA')", table);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)", user, other, other, user);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, user, other);
        Jwt validated = decoder.decode(loginAs("socialexpire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM table_invites WHERE id = ? FOR UPDATE", UUID.class, invite);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var mutation = executor.submit(() ->
                    socialMutations.mutate(expiring, other, "expire-block", SocialMutationService.Operation.BLOCK));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'UPDATE table_invites SET status = %'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> mutation.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, user, other)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                        OR (follower_id = ? AND followee_id = ?)
                    """, Integer.class, user, other, other, user)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invitationResponsesAndEligibilityChangesAreTransactionalAndRetrySafe() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID declineTable = UUID.randomUUID();
        UUID staleTable = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        UUID decline = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'respondviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'respondhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Response', 'T', 'Y4R6QA'), (?, 'Decline', 'T', 'Y4R7QA'),
                (?, 'Stale', 'T', 'Y4R8QA')
                """, table, declineTable, staleTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'HOST')
                """, table, host, declineTable, host, staleTable, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES
                (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, invite, table, host, recipient, decline, declineTable, host, recipient,
                stale, staleTable, host, recipient);
        Cookie session = loginAs("respondviewer");
        Cookie hostSession = loginAs("respondhost");
        Csrf csrf = csrf();
        mvc.perform(invitePreferencesPut(session, csrf, "open-invites",
                "{\"friends\":\"closed\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, hostSession, csrf, "wrong-user", true))
                .andExpect(status().isNotFound());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", false)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                String.class, table, recipient)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND kind = 'system'",
                Integer.class, table)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, table.toString())).isEqualTo(2);
        mvc.perform(invitationResponse(decline, session, csrf, "decline", false)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(decline, session, csrf, "decline", false)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, decline))
                .isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, declineTable, recipient)).isZero();
        mvc.perform(invitePreferencesPut(session, csrf, "close-invites",
                "{\"friends\":\"open\",\"following\":\"closed\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, stale))
                .isEqualTo("CANCELLED");
        mvc.perform(invitePreferencesPut(session, csrf, "reopen-invites",
                "{\"friends\":\"open\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(stale, session, csrf, "stale", false)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, stale))
                .isEqualTo("CANCELLED");
        UUID noHost = UUID.randomUUID();
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                noHost, staleTable, host, recipient);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", staleTable, host);
        mvc.perform(invitationResponse(noHost, session, csrf, "no-host", true)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, noHost))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ? AND operation = ?",
                Integer.class, recipient, "invitation.respond.v1")).isEqualTo(2);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, recipient);
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNotFound());
    }

    @Test
    void followChangesCancelIneligibleInvitationsWithoutRestoringThem() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'followinvitee', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'followinviter', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Follow', 'T', 'Y4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, recipient);
        Cookie session = loginAs("followinvitee");
        Cookie hostSession = loginAs("followinviter");
        Csrf csrf = csrf();
        mvc.perform(invitePreferencesPut(session, csrf, "close-friends",
                "{\"friends\":\"closed\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", recipient, hostSession, csrf, "make-friends"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", recipient, hostSession, csrf, "stop-friends"))
                .andExpect(status().isNoContent());
        UUID secondInvite = UUID.randomUUID();
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                secondInvite, table, host, recipient);
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", host, session, csrf, "unfollow-invite"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", host, session, csrf, "refollow-invite"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, secondInvite))
                .isEqualTo("CANCELLED");
    }

    @Test
    void concurrentInvitationAcceptanceAndBlockSerialize() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteracea', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'inviteraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Y5R6QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, recipient);
        Jwt recipientJwt = decoder.decode(loginAs("inviteracea").getValue());
        Jwt hostJwt = decoder.decode(loginAs("inviteraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var response = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitationResponses.respond(recipientJwt, invite, true, "race-accept");
            });
            var block = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(hostJwt, recipient, "race-block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            InvitationResponseService.Outcome outcome = response.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isIn(InvitationResponseService.Outcome.UPDATED, InvitationResponseService.Outcome.NOT_FOUND);
            assertThat(block.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo(outcome == InvitationResponseService.Outcome.UPDATED ? "ACCEPTED" : "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                    Integer.class, table, recipient)).isEqualTo(outcome == InvitationResponseService.Outcome.UPDATED ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invitationResponse(
            UUID invite, Cookie session, Csrf csrf, String key, boolean accept) {
        return post("/invites/" + invite + "/respond").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accept\":" + accept + "}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder socialRequest(
            HttpMethod method, String prefix, UUID target, Cookie session, Csrf csrf, String key) {
        return request(method, prefix + target).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }

    @Test
    void reactionsValidateReplaceRemoveAndRecheckAccessOnRetry() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reactactor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reactauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reactoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, author, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Reactions', 'T', 'R2X4QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, actor, table, author);
        UUID bet = insertDebtBet(table, actor, author, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Hello'), (?, ?, 'system', NULL, 'System')
                """, text, table, author, system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        Cookie session = loginAs("reactactor");
        Cookie authorSession = loginAs("reactauthor");
        Cookie outsideSession = loginAs("reactoutside");
        Csrf csrf = csrf();
        mvc.perform(put("/messages/" + text + "/reaction").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"😀\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/messages/" + text + "/reaction").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"😀\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "bad-delete").content("unexpected"))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"emoji\":\"\"}", "{\"emoji\":\"hello\"}",
                "{\"emoji\":\"😀😀\"}", "{\"emoji\":\"😀 \"}", "{\"emoji\":1}", "{\"emoji\":\"😀\",\"x\":1}")) {
            mvc.perform(reactionPut(text, session, csrf, "bad-reaction", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(reactionPut(text, outsideSession, csrf, "outside", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(system, session, csrf, "system", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        MvcResult result = mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(reactionPut(text, authorSession, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "replace", "{\"emoji\":\"👨‍👩‍👧‍👦\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isConflict());
        mvc.perform(reactionPut(gif, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isConflict());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "first"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT emoji FROM reactions WHERE message_id = ? AND user_id = ?",
                String.class, text, actor)).isEqualTo("👨‍👩‍👧‍👦");
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove-again"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "same-value", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reactions WHERE message_id = ?", Integer.class, text))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'chat.reaction.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, table.toString(), table.toString())).isEqualTo(5);
        jdbc.update("UPDATE table_members SET can_react = false WHERE table_id = ? AND user_id = ?", table, actor);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE table_members SET can_react = true WHERE table_id = ? AND user_id = ?", table, actor);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", actor, author);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(gif, session, csrf, "gif", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(betMessage, session, csrf, "bet", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", actor, author);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", author, actor);
        mvc.perform(reactionPut(gif, session, csrf, "gif", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, actor);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(reactionPut(text, authorSession, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reactionExpiryRollsBackClaimAndWrite() throws Exception {
        UUID user = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'reactexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'R2X5QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, user);
        jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀')", message, user);
        Jwt validated = decoder.decode(loginAs("reactexpiry").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT message_id FROM reactions WHERE message_id = ? AND user_id = ? FOR UPDATE",
                        UUID.class, message, user);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> reactions.change(expiring, message, "👍", "reaction-expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO reactions (message_id, user_id, emoji)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT emoji FROM reactions WHERE message_id = ? AND user_id = ?",
                    String.class, message, user)).isEqualTo("😀");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reactionPut(
            UUID message, Cookie session, Csrf csrf, String key, String body) {
        return reactionRequest(HttpMethod.PUT, message, session, csrf, key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reactionRequest(
            HttpMethod method, UUID message, Cookie session, Csrf csrf, String key) {
        return request(method, "/messages/" + message + "/reaction").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }

    @Test
    void chatReadStateFiltersAndAdvances() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID own = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'readchatuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readchatauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readchatoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Unread', 'T', 'C4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, user, table, author);
        UUID bet = insertDebtBet(table, user, author, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Own'), (?, ?, 'text', ?, 'First')
                """, own, table, user, first, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'System')", system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Last')",
                last, table, author);
        Cookie session = loginAs("readchatuser");
        Cookie outside = loginAs("readchatoutside");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(get("/tables/" + table + "/last-read")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tables/" + table + "/last-read").cookie(outside)).andExpect(status().isNotFound());
        mvc.perform(put("/tables/" + table + "/last-read").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isForbidden());
        assertUnread(table, session, first, 5);
        for (String invalid : List.of("{}", "{\"messageId\":\"bad\"}", "{\"messageId\":\"1-1-1-1-1\"}",
                "{\"messageId\":null}", "{\"messageId\":\"" + first + "\",\"extra\":true}")) {
            mvc.perform(chatReadPut(table, session, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        MvcResult saved = mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertUnread(table, session, gif, 4);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + gif + "\"}"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        assertUnread(table, session, system, 2);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "blocked", "{\"messageId\":\"" + gif + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "system", "{\"messageId\":\"" + system + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, betMessage, 1);
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", user, author);
        assertUnread(table, session, betMessage, 2);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(chatReadPut(table, session, csrf, "older", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, betMessage, 2);
        mvc.perform(chatReadPut(table, session, csrf, "last", "{\"messageId\":\"" + last + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, null, 0);
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", last);
        UUID after = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'After')",
                after, table, author);
        UUID deletedAuthor = UUID.randomUUID();
        UUID deletedText = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, role, status, deleted_at) VALUES (?, 'PLAYER', 'DELETED', now())
                """, deletedAuthor);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hidden')",
                deletedText, table, deletedAuthor);
        assertUnread(table, session, after, 1);
        mvc.perform(chatReadPut(table, session, csrf, "deleted", "{\"messageId\":\"" + deletedText + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "last", "{\"messageId\":\"" + last + "\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(get("/tables/" + table + "/last-read").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "after", "{\"messageId\":\"" + after + "\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/tables/" + table + "/last-read").cookie(loginAs("readchatauthor")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatRead.read(validated, table).authenticated()).isFalse();
        assertThat(chatRead.advance(validated, table, after, "after")).isEqualTo(ChatReadService.Outcome.UNAUTHORIZED);
    }

    @Test
    void chatReadExpiryRollsBackPositionAndRetryClaim() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'readexpire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readexpirepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'C4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Previous')",
                previous, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, author);
        Long previousOrder = jdbc.queryForObject("SELECT message_order FROM chat_messages WHERE id = ?", Long.class, previous);
        jdbc.update("INSERT INTO chat_read_positions (table_id, user_id, last_read_order) VALUES (?, ?, ?)",
                table, user, previousOrder);
        Jwt validated = decoder.decode(loginAs("readexpire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT user_id FROM chat_read_positions WHERE table_id = ? AND user_id = ? FOR UPDATE",
                        UUID.class, table, user);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> chatRead.advance(expiring, table, message, "read-expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO chat_read_positions (table_id, user_id, last_read_order)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT last_read_order FROM chat_read_positions WHERE user_id = ?",
                    Long.class, user)).isEqualTo(previousOrder);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertUnread(UUID table, Cookie session, UUID first, long count) throws Exception {
        MvcResult result = mvc.perform(get("/tables/" + table + "/last-read").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode state = json.readTree(result.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("ChatUnreadState").validate(state)).isEmpty();
        assertThat(state.path("unreadCount").asLong()).isEqualTo(count);
        if (first == null) assertThat(state.path("firstUnreadMessageId").isNull()).isTrue();
        else assertThat(state.path("firstUnreadMessageId").asText()).isEqualTo(first.toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatReadPut(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return put("/tables/" + table + "/last-read").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void chatWindowsPageInStableOrderAndRecheckAccess() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'windowuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'windowauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'windowoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Window', 'T', 'W2R7QA'), (?, 'Other', 'T', 'W2R8QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, table, user, table, author, otherTable, user);
        List<UUID> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            UUID id = UUID.randomUUID();
            messages.add(id);
            jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                    id, table, author, "Message " + i);
        }
        Cookie session = loginAs("windowuser");
        Cookie otherSession = loginAs("windowauthor");
        Cookie outside = loginAs("windowoutside");
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(get("/chat/" + table + "/messages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/chat/" + table + "/messages").cookie(outside)).andExpect(status().isNotFound());
        JsonNode latest = chatWindow(table, session, null, null);
        assertWindowItems(latest, messages.subList(50, 80));
        String tail = latest.path("nextCursor").asText();
        assertThat(tail).isNotBlank();
        assertThat(chatWindow(table, session, "after", tail).path("items").isEmpty()).isTrue();
        String previous = latest.path("previousCursor").asText();
        assertThat(previous).isNotBlank().doesNotContain(messages.get(50).toString());
        mvc.perform(get("/chat/" + table + "/messages").cookie(otherSession).param("before", previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + otherTable + "/messages").cookie(session).param("before", previous))
                .andExpect(status().isBadRequest());
        String tampered = (previous.charAt(0) == 'A' ? "B" : "A") + previous.substring(1);
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", tampered))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous).param("after", previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous, previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("around", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("around", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        JsonNode around = chatWindow(table, session, "around", messages.get(40).toString());
        assertWindowItems(around, messages.subList(35, 65));
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", messages.get(50));
        JsonNode middle = chatWindow(table, session, "before", previous);
        assertWindowItems(middle, messages.subList(25, 50));
        JsonNode earliest = chatWindow(table, session, "before", middle.path("previousCursor").asText());
        assertWindowItems(earliest, messages.subList(0, 25));
        assertThat(earliest.path("previousCursor").isNull()).isTrue();
        JsonNode forward = chatWindow(table, session, "after", earliest.path("nextCursor").asText());
        assertWindowItems(forward, messages.subList(25, 50));
        String afterMiddle = middle.path("nextCursor").asText();
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", messages.get(49));
        JsonNode forwardAfterDeletedAnchor = chatWindow(table, session, "after", afterMiddle);
        assertWindowItems(forwardAfterDeletedAnchor, messages.subList(51, 76));
        List<UUID> arrivals = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            UUID id = UUID.randomUUID();
            arrivals.add(id);
            jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                    id, table, author, "Arrival " + i);
        }
        JsonNode newPage = chatWindow(table, session, "after", tail);
        assertWindowItems(newPage, arrivals.subList(0, 25));
        JsonNode remaining = chatWindow(table, session, "after", newPage.path("nextCursor").asText());
        assertWindowItems(remaining, arrivals.subList(25, 31));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        JsonNode hidden = chatWindow(table, session, null, null);
        assertThat(hidden.path("items").isEmpty()).isTrue();
        assertThat(hidden.path("previousCursor").isNull()).isTrue();
        assertThat(hidden.path("nextCursor").isNull()).isTrue();
        mvc.perform(get("/chat/" + table + "/messages").cookie(session)
                .param("around", messages.get(40).toString())).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", user, author);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(otherSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatWindows.read(validated, table,
                new ChatWindowService.Query(ChatWindowService.Direction.LATEST, null, null)).authenticated()).isFalse();
    }

    @Test
    void chatWindowsProjectOnlyAuthorizedMessageFields() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID settlementMessage = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'projectionuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'projectionpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash);
        jdbc.update("INSERT INTO users (id, role, status, deleted_at) VALUES (?, 'PLAYER', 'DELETED', now())", deleted);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Projection', 'T', 'W2R9QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, user, table, author);
        UUID bet = insertDebtBet(table, user, author, "ACTIVE", false, false, false, 100, 0);
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlement, table, author);
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 123, 'sent')
                """, UUID.randomUUID(), settlement, table, author, user, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hello')",
                text, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'System')", system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                settlementMessage, table, settlement);
        jdbc.update("""
                INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀'), (?, ?, '👍'), (?, ?, '🔥')
                """, betMessage, user, betMessage, author, betMessage, deleted);
        Cookie session = loginAs("projectionuser");
        JsonNode window = chatWindow(table, session, null, null);
        assertWindowItems(window, List.of(text, gif, betMessage, system, settlementMessage));
        for (JsonNode message : window.path("items")) {
            assertThat(OpenApiSchemas.load("ChatMessage").validate(message)).isEmpty();
        }
        JsonNode projectedBet = window.path("items").get(2);
        assertThat(projectedBet.path("betId").asText()).isEqualTo(bet.toString());
        assertThat(projectedBet.has("picks")).isFalse();
        assertThat(projectedBet.path("reactions").size()).isEqualTo(2);
        JsonNode summary = window.path("items").get(4).path("settlementSummary");
        assertThat(summary.path("hostId").asText()).isEqualTo(author.toString());
        assertThat(summary.path("lines").get(0).path("amountCents").asLong()).isEqualTo(123);
        assertThat(summary.path("lines").get(0).has("status")).isFalse();
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        JsonNode filtered = chatWindow(table, session, null, null);
        assertWindowItems(filtered, List.of(betMessage, system, settlementMessage));
        assertThat(filtered.path("items").get(0).path("reactions").size()).isEqualTo(2);
    }

    private JsonNode chatWindow(UUID table, Cookie session, String parameter, String value) throws Exception {
        var request = get("/chat/" + table + "/messages").cookie(session);
        if (parameter != null) request.param(parameter, value);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertWindowItems(JsonNode window, List<UUID> expected) {
        List<String> actual = new java.util.ArrayList<>();
        window.path("items").forEach(message -> actual.add(message.path("id").asText()));
        assertThat(actual).containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
    }

    @Test
    void chatSendValidatesPermissionsAndReplaysOriginalResponse() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'sendchatuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'sendchatother', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Chat', 'T', 'S4R7QA'), (?, 'Other', 'T', 'S4R8QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'HOST')
                """, table, user, table, other, otherTable, user);
        Cookie session = loginAs("sendchatuser");
        Cookie otherSession = loginAs("sendchatother");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(post("/tables/" + table + "/messages").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"text\",\"text\":\"Hi\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/tables/" + table + "/messages").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tables/" + table + "/messages").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"kind\":\"system\",\"text\":\"Hi\"}",
                "{\"kind\":\"text\",\"text\":\"  \\t \"}", "{\"kind\":\"text\",\"text\":\"\\u0000\"}",
                "{\"kind\":\"text\",\"text\":\"\\uD800\"}", "{\"kind\":\"text\",\"text\":4}",
                "{\"kind\":\"text\",\"text\":\"Hi\",\"extra\":true}",
                "{\"kind\":\"gif\",\"gifId\":\"g25\"}", "{\"kind\":\"gif\",\"gifId\":\" g1 \"}",
                "{\"kind\":\"gif\",\"gifId\":\"g01\"}")) {
            mvc.perform(chatSendPost(table, session, csrf, "bad-send", invalid)).andExpect(status().isBadRequest());
        }
        String tooLong = json.writeValueAsString(json.createObjectNode().put("kind", "text").put("text", "😀".repeat(2001)));
        mvc.perform(chatSendPost(table, session, csrf, "bad-send", tooLong)).andExpect(status().isBadRequest());
        String limit = json.writeValueAsString(json.createObjectNode().put("kind", "text").put("text", "😀".repeat(2000)));
        MvcResult maxLength = mvc.perform(chatSendPost(otherTable, session, csrf, "limit", limit))
                .andExpect(status().isCreated()).andReturn();
        String savedText = json.readTree(maxLength.getResponse().getContentAsString()).path("text").asText();
        assertThat(savedText).isEqualTo("😀".repeat(2000));
        mvc.perform(chatSendPost(otherTable, otherSession, csrf, "outside",
                "{\"kind\":\"text\",\"text\":\"Hi\"}")).andExpect(status().isNotFound());
        MvcResult created = mvc.perform(chatSendPost(table, session, csrf, "first",
                "{\"kind\":\"text\",\"text\":\"  Hello \\uFEFF\"}")).andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode response = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("ChatMessage").validate(response)).isEmpty();
        assertThat(response.path("text").asText()).isEqualTo("Hello");
        UUID message = UUID.fromString(response.path("id").asText());
        jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀')", message, other);
        MvcResult replay = mvc.perform(chatSendPost(table, session, csrf, "first",
                "{\"kind\":\"text\",\"text\":\"Hello\"}")).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(response);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Changed\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(otherTable, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(table, otherSession, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g24\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'chat.message.created.v1'
                    AND payload = jsonb_build_object('tableId', ?::text, 'messageId', ?::text, 'kind', 'text')
                """, Integer.class, table.toString(), table.toString(), message.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM http_idempotency WHERE user_id = ? AND response_resource_id = ?
                """, Integer.class, user, message)).isEqualTo(1);
        assertThat(chatWindow(table, session, null, null).path("items").size()).isEqualTo(2);
        jdbc.update("UPDATE table_members SET can_text = false WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(chatSendPost(table, session, csrf, "gif", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE table_members SET can_text = true, can_send_gifs = false WHERE table_id = ? AND user_id = ?",
                table, user);
        mvc.perform(chatSendPost(table, session, csrf, "gif", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE chat_messages SET text = 'Redacted' WHERE id = ?", message);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", message);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE response_resource_id = ?",
                Integer.class, message)).isEqualTo(1);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(chatSendPost(table, session, csrf, "new", "{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(chatSendPost(table, otherSession, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g24\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatSend.send(validated, table, "first", new ChatSendService.Submission("text", "Hello")).outcome())
                .isEqualTo(ChatSendService.Outcome.UNAUTHORIZED);
    }

    @Test
    void chatSendSerializesDuplicateRetriesAndRollsBackOnExpiry() throws Exception {
        UUID user = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'sendchatrace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'S4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        Jwt validated = decoder.decode(loginAs("sendchatrace").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(validated, table, "concurrent", new ChatSendService.Submission("text", "Race"));
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(validated, table, "concurrent", new ChatSendService.Submission("text", "Race"));
            });
            start.countDown();
            ChatSendService.Result one = first.get(10, TimeUnit.SECONDS);
            ChatSendService.Result two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one.outcome(), two.outcome()))
                    .containsExactlyInAnyOrder(ChatSendService.Outcome.CREATED, ChatSendService.Outcome.REPLAYED);
            assertThat(one.message()).isEqualTo(two.message());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var send = executor.submit(() -> chatSend.send(expiring, table, "expiry",
                    new ChatSendService.Submission("text", "Rollback")));
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
            assertThatThrownBy(() -> send.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatSendPost(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return post("/tables/" + table + "/messages").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void hostPermissionsAreQuietRetrySafeAndEnforcedByChat() throws Exception {
        UUID host = UUID.randomUUID();
        UUID otherHost = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permhostpeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permobserver', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, otherHost, hash, member, hash, observer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Permission', 'T', 'P4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, otherHost, table, member, table, observer);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, host);
        Cookie hostSession = loginAs("permhost");
        Cookie memberSession = loginAs("permmember");
        Cookie observerSession = loginAs("permobserver");
        Cookie outsideSession = loginAs("permoutside");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(hostSession.getValue());
        String disableText = "{\"permission\":\"text\",\"enabled\":false}";
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON).content(disableText))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(disableText)).andExpect(status().isForbidden());
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(disableText)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"permission\":\"text\"}", "{\"permission\":\"text\",\"enabled\":\"true\"}",
                "{\"permission\":\"bad\",\"enabled\":true}", "{\"permission\":\"text\",\"enabled\":true,\"extra\":1}")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(permissionPut(table, member, outsideSession, csrf, "outside", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, observer, memberSession, csrf, "non-host", disableText))
                .andExpect(status().isForbidden());
        mvc.perform(permissionPut(table, outsider, hostSession, csrf, "not-member", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, UUID.randomUUID(), hostSession, csrf, "missing", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, host, hostSession, csrf, "self", disableText)).andExpect(status().isConflict());
        mvc.perform(permissionPut(table, otherHost, hostSession, csrf, "other-host", disableText))
                .andExpect(status().isConflict());
        MvcResult result = mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(chatSendPost(table, memberSession, csrf, "text-blocked", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isConflict());
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isConflict());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-on",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isNoContent());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent());
        mvc.perform(chatSendPost(table, memberSession, csrf, "text-allowed", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isCreated());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-noop",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isNoContent());
        for (String permission : List.of("bets", "react", "gifs")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, permission + "-off",
                    "{\"permission\":\"" + permission + "\",\"enabled\":false}")).andExpect(status().isNoContent());
        }
        mvc.perform(chatSendPost(table, memberSession, csrf, "gif-blocked", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(reactionPut(message, memberSession, csrf, "react-blocked", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isForbidden());
        JsonNode hostTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode memberTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode observerTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(observerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostTable.path("disabledPermissions").path(member.toString()).size()).isEqualTo(3);
        assertThat(memberTable.path("disabledPermissions").path(member.toString()).size()).isEqualTo(3);
        assertThat(observerTable.path("disabledPermissions").has(member.toString())).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.member.permission.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text, 'userId', ?::text)
                """, Integer.class, table.toString(), table.toString(), member.toString())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND kind = 'system'",
                Integer.class, table)).isZero();
        for (String permission : List.of("bets", "react", "gifs")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, permission + "-on",
                    "{\"permission\":\"" + permission + "\",\"enabled\":true}")).andExpect(status().isNoContent());
        }
        mvc.perform(chatSendPost(table, memberSession, csrf, "gif-allowed", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(reactionPut(message, memberSession, csrf, "react-allowed", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", observer);
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "frozen-target", disableText))
                .andExpect(status().isNoContent());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, observer);
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "frozen-target", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("""
                UPDATE table_members SET role = 'HOST', can_bet = true, can_text = true,
                    can_react = true, can_send_gifs = true WHERE table_id = ? AND user_id = ?
                """, table, member);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(permissionPut(table, observer, loginAs("permhostpeer"), csrf, "archived", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        assertThat(tablePermissions.set(validated, table, member, TablePermissionService.Permission.TEXT, false, "text-off"))
                .isEqualTo(TablePermissionService.Outcome.UNAUTHORIZED);
    }

    @Test
    void permissionChangeSerializesWithMemberChatSend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permracemember', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'P4R8QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, table, host, table, member);
        Jwt hostJwt = decoder.decode(loginAs("permracehost").getValue());
        Jwt memberJwt = decoder.decode(loginAs("permracemember").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var change = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tablePermissions.set(hostJwt, table, member, TablePermissionService.Permission.TEXT, false, "race");
            });
            var send = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(memberJwt, table, "race-send", new ChatSendService.Submission("text", "Hello"));
            });
            start.countDown();
            assertThat(change.get(10, TimeUnit.SECONDS)).isEqualTo(TablePermissionService.Outcome.UPDATED);
            ChatSendService.Outcome result = send.get(10, TimeUnit.SECONDS).outcome();
            assertThat(result).isIn(ChatSendService.Outcome.CREATED, ChatSendService.Outcome.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(result == ChatSendService.Outcome.CREATED ? 1 : 0);
            assertThat(jdbc.queryForObject("SELECT can_text FROM table_members WHERE table_id = ? AND user_id = ?",
                    Boolean.class, table, member)).isFalse();
            assertThat(chatSend.send(memberJwt, table, "after-race", new ChatSendService.Submission("text", "Later"))
                    .outcome()).isEqualTo(ChatSendService.Outcome.FORBIDDEN);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void permissionExpiryRollsBackClaimAndWrite() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permexpiryhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permexpirymem', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'P4X9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        Jwt validated = decoder.decode(loginAs("permexpiryhost").getValue());
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
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tablePermissions.set(
                    expiring, table, member, TablePermissionService.Permission.TEXT, false, "expiry"));
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
            assertThat(jdbc.queryForObject("SELECT can_text FROM table_members WHERE table_id = ? AND user_id = ?",
                    Boolean.class, table, member)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder permissionPut(
            UUID table, UUID target, Cookie session, Csrf csrf, String key, String body) {
        return put("/tables/" + table + "/members/" + target + "/permissions").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void tableProfileChangesRespectAccessRetriesAndInvitationViews() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profile_host', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_member', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_invitee', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_outside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, invitee, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Before', '🏈', 'Y6R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", invitee, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invitation, table, host, invitee);
        Cookie hostSession = loginAs("profile_host");
        Cookie memberSession = loginAs("profile_member");
        Cookie inviteeSession = loginAs("profile_invitee");
        Cookie outsideSession = loginAs("profile_outside");
        Csrf csrf = csrf();
        String both = "{\"name\":\"  New title  \",\"emoji\":\"🎲\"}";
        mvc.perform(patch("/tables/" + table).cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table).cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"name\":null}", "{\"emoji\":false}", "{\"name\":\"   \"}",
                "{\"name\":\"x\\u0000y\"}", "{\"name\":\"\\uD800\"}",
                "{\"name\":\"" + "🙂".repeat(41) + "\"}", "{\"emoji\":\"not emoji\"}",
                "{\"name\":\"valid\",\"other\":true}")) {
            mvc.perform(profilePatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(profilePatch(table, memberSession, csrf, "member", both)).andExpect(status().isForbidden());
        mvc.perform(profilePatch(table, outsideSession, csrf, "outside", both)).andExpect(status().isNotFound());
        mvc.perform(profilePatch(UUID.randomUUID(), hostSession, csrf, "missing", both)).andExpect(status().isNotFound());
        MvcResult first = mvc.perform(profilePatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(first.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(view)).isEmpty();
        assertThat(view.path("name").asText()).isEqualTo("New title");
        assertThat(view.path("emoji").asText()).isEqualTo("🎲");
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT text FROM chat_messages WHERE table_id = ?", String.class, table))
                .isEqualTo("Table details changed");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.profile.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, table.toString(), table.toString())).isEqualTo(1);
        MvcResult inbox = mvc.perform(get("/me/invites").cookie(inviteeSession)).andExpect(status().isOk()).andReturn();
        JsonNode inviteView = json.readTree(inbox.getResponse().getContentAsString()).get(0);
        assertThat(inviteView.path("tableName").asText()).isEqualTo("New title");
        assertThat(inviteView.path("tableEmoji").asText()).isEqualTo("🎲");
        mvc.perform(profilePatch(table, hostSession, csrf, "first", "{\"name\":\"New title\",\"emoji\":\"🎲\"}"))
                .andExpect(status().isOk());
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", "{\"hostsOnlyBets\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(profilePatch(table, hostSession, csrf, "first", "{\"name\":\"Different\"}"))
                .andExpect(status().isConflict());
        mvc.perform(profilePatch(table, hostSession, csrf, "second", "{\"name\":\"Later\"}"))
                .andExpect(status().isOk());
        MvcResult replay = mvc.perform(profilePatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("name").asText()).isEqualTo("Later");
        mvc.perform(profilePatch(table, hostSession, csrf, "noop", "{\"name\":\"  Later  \"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        mvc.perform(profilePatch(table, hostSession, csrf, "max", "{\"name\":\"" + "🙂".repeat(40) + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND text <> 'Table details changed'",
                Integer.class, table)).isZero();
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(profilePatch(table, hostSession, csrf, "first", both)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentTableProfilePartialUpdatesPreserveBothChanges() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profileracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profileraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', '🏈', 'Y6R7QB')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("profileracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("profileraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableProfile.update(firstJwt, table, new TableProfileService.Changes("New", null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableProfile.update(secondJwt, table, new TableProfileService.Changes(null, "🎲"), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(TableProfileService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(TableProfileService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT name = 'New' AND emoji = '🎲' FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableProfileExpiryRollsBackChangesAndEvents() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profile_expire', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Before', '🏈', 'Y6R7QC')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("profile_expire").getValue());
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
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableProfile.update(expiring, table,
                    new TableProfileService.Changes("After", "🎲"), "expiry"));
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
            assertThat(jdbc.queryForObject("SELECT name = 'Before' AND emoji = '🏈' FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder profilePatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void userProfileUpdatesNormalizeAndPreserveSessionsAndRetrySafety() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profileuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profiletaken', 2, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Profiles', 'T', 'U8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, user);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', 'A member is now a host', 'host_promoted', ?)
                """, UUID.randomUUID(), table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                UUID.randomUUID(), table, "profileuser joined the table");
        Cookie firstSession = loginAs("profileuser");
        Cookie secondSession = loginAs("profileuser");
        Csrf csrf = csrf();
        for (String invalid : List.of("{}", "{\"username\":null}", "{\"hue\":true}", "{\"hue\":1.0}",
                "{\"hue\":0}", "{\"hue\":6}", "{\"username\":\"ab\"}", "{\"username\":\"a.b\"}",
                "{\"username\":\"abc\\u0000\"}", "{\"username\":\"abc\\uD800\"}", "{\"extra\":1}")) {
            mvc.perform(userProfilePatch(firstSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/me/profile").cookie(firstSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "missing-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hue\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/me/profile").cookie(firstSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hue\":3}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"PROFILETAKEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"\\u00a0New_Name\\uFEFF\",\"hue\":5}"))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(userProfilePatch(firstSession, csrf, "taken",
                "{\"hue\":5,\"username\":\"new_name\"}")).andExpect(status().isOk()).andReturn();
        JsonNode profile = json.readTree(result.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("User").validate(profile)).isEmpty();
        assertThat(profile.path("username").asText()).isEqualTo("new_name");
        assertThat(profile.path("hue").asInt()).isEqualTo(5);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(mvc.perform(get("/me").cookie(secondSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("username").asText()).isEqualTo("new_name");
        JsonNode chat = chatWindow(table, firstSession, null, null).path("items");
        assertThat(chat.get(0).path("text").asText()).isEqualTo("new_name is now a host");
        assertThat(chat.get(1).path("text").asText()).isEqualTo("profileuser joined the table");
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"hue\":4}")).andExpect(status().isConflict());
        mvc.perform(userProfilePatch(firstSession, csrf, "hue", "{\"hue\":3}")).andExpect(status().isOk());
        MvcResult replay = mvc.perform(userProfilePatch(firstSession, csrf, "taken",
                "{\"username\":\"new_name\",\"hue\":5}")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("hue").asInt()).isEqualTo(3);
        mvc.perform(userProfilePatch(firstSession, csrf, "noop", "{\"hue\":3}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'user.profile.changed.v1'
                    AND payload = jsonb_build_object('userId', ?::text)
                """, Integer.class, user.toString(), user.toString())).isEqualTo(2);
        Csrf loginCsrf = csrf();
        mvc.perform(post("/auth/login").cookie(loginCsrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", loginCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"profileuser\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isUnauthorized());
        loginAs("\u00a0NEW_NAME\uFEFF");
        jdbc.update("UPDATE users SET username = 'profileuser' WHERE id = ?", other);
        assertThat(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class, other))
                .isEqualTo("profileuser");
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"new_name\",\"hue\":5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentUserProfilePartialUpdatesPreserveBothChanges() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profilpart', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt first = decoder.decode(loginAs("profilpart").getValue());
        Jwt second = decoder.decode(loginAs("profilpart").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return userProfiles.update(first, new UserProfileService.Changes("profilnew", null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return userProfiles.update(second, new UserProfileService.Changes(null, 5), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(UserProfileService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(UserProfileService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT username = 'profilnew'::citext AND hue = 5 FROM users WHERE id = ?",
                    Boolean.class, user)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentUserProfileUpdatesEnforceUniqueUsernames() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profilraceone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profilracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        Jwt one = decoder.decode(loginAs("profilraceone").getValue());
        Jwt two = decoder.decode(loginAs("profilracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> updateOne = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    userProfiles.update(one, new UserProfileService.Changes("raced_name", null), "one");
                    return true;
                } catch (UserProfileService.UsernameTaken exception) {
                    return false;
                }
            };
            java.util.concurrent.Callable<Boolean> updateTwo = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    userProfiles.update(two, new UserProfileService.Changes("raced_name", null), "two");
                    return true;
                } catch (UserProfileService.UsernameTaken exception) {
                    return false;
                }
            };
            var firstResult = executor.submit(updateOne);
            var secondResult = executor.submit(updateTwo);
            start.countDown();
            assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'raced_name'::citext",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void userProfileExpiryRollsBackUserOutboxAndRetryClaim() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profileexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt validated = decoder.decode(loginAs("profileexpiry").getValue());
        verifyMutationExpiry(validated,
                jwt -> userProfiles.update(jwt, new UserProfileService.Changes("afterexpiry", 5), "expiry"));
        assertThat(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class, user))
                .isEqualTo("profileexpiry");
        assertThat(jdbc.queryForObject("SELECT hue FROM users WHERE id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, user.toString()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder userProfilePatch(
            Cookie session, Csrf csrf, String key, String body) {
        return patch("/me/profile").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void tableArchiveProtectsFinancialStateAndProvidesTimedFilteredReadOnlyChat() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID former = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'archhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archformer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, former, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'Archived', 'T', 'A8R7QA', true, true), (?, 'Other', 'T', 'A8R7QB', true, true)
                """, table, other);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, former);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, former);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hello')",
                message, table, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, host, former);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)",
                table, other, other, table);
        UUID bet = insertDebtBet(table, member, host, "ACTIVE", false, false, false, 100, 0);
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, source_bet_id, source_table_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Copy?', '["Yes","No"]'::jsonb, 100, false, now(), ?, ?)
                """, copy, other, host, bet, table);
        UUID receipt = insertDebtReceipt(table, host, member, host, 100, "unpaid");
        Cookie hostSession = loginAs("archhost");
        Cookie memberSession = loginAs("archmember");
        Cookie formerSession = loginAs("archformer");
        Csrf csrf = csrf();
        String path = "/tables/" + table;
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, memberSession, csrf, "member", null))
                .andExpect(status().isForbidden());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, formerSession, csrf, "former", null))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'earliest' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", bet);
        MvcResult success = mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(success.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(get(path).cookie(hostSession)).andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isNoContent());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "new", null))
                .andExpect(status().isNotFound());
        JsonNode archiveList = json.readTree(mvc.perform(get("/tables/archives").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(archiveList.size()).isEqualTo(1);
        assertThat(OpenApiSchemas.load("TableArchive").validate(archiveList.get(0))).isEmpty();
        assertThat(archiveList.get(0).path("id").asText()).isEqualTo(table.toString());
        assertThat(archiveList.get(0).path("name").asText()).isEqualTo("Archived");
        var archivedAt = java.time.Instant.parse(archiveList.get(0).path("archivedAt").asText());
        var expiresAt = java.time.Instant.parse(archiveList.get(0).path("expiresAt").asText());
        assertThat(java.time.Duration.between(archivedAt, expiresAt)).isEqualTo(java.time.Duration.ofDays(90));
        assertThat(json.readTree(mvc.perform(get("/tables/archives").cookie(formerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        assertThat(chatWindow(table, memberSession, null, null).path("items").size()).isEqualTo(1);
        mvc.perform(chatSendPost(table, memberSession, csrf, "archive-chat", "{\"kind\":\"text\",\"text\":\"No\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.PUT, "/messages/" + message + "/reaction", memberSession, csrf,
                "archive-reaction", "{\"emoji\":\"👍\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/chat/" + table + "/messages").cookie(formerSession)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", member, host);
        assertThat(chatWindow(table, memberSession, null, null).path("items").isEmpty()).isTrue();
        mvc.perform(quickPickRequest(HttpMethod.PUT, path + "/quick-picks", hostSession, csrf, "enable",
                "{\"enabled\":true}")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ? OR subscriber_table_id = ?",
                Integer.class, table, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id = ? AND status = 'unpaid'",
                Integer.class, receipt)).isEqualTo(1);
        assertThat(json.readTree(mvc.perform(get("/me/debt").cookie(memberSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("debtCents").asLong()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id = ?", Integer.class, bet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_bet_id FROM bets WHERE id = ?", UUID.class, copy)).isEqualTo(bet);
        jdbc.update("UPDATE tables SET archived_at = clock_timestamp() - interval '2159 hours' WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession)).andExpect(status().isOk());
        jdbc.update("UPDATE tables SET archived_at = clock_timestamp() - interval '2160 hours' WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession)).andExpect(status().isNotFound());
        assertThat(json.readTree(mvc.perform(get("/tables/archives").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE id = ?", Integer.class, message))
                .isEqualTo(1);
    }

    @Test
    void tableArchiveRejectsMembershipRaceAndRollsBackOnExpiry() throws Exception {
        UUID host = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'archracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archracemember', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, added, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'A8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("archracehost").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, host);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, added);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var archive = executor.submit(() -> tableArchives.archive(validated, table, "race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> archive.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TableArchiveService.MembershipChanged.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        verifyMutationExpiry(validated, jwt -> tableArchives.archive(jwt, table, "archive-expiry"));
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_archive_members WHERE table_id = ?",
                Integer.class, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, table.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                Integer.class, host)).isZero();
    }

    @Test
    void memberRemovalProtectsUnsettledBetsAndReplaysWithoutRemovingAgain() throws Exception {
        UUID host = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'rmhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rmtarget', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rmoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, target, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Removal', 'T', 'M8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, host, table, target);
        Cookie hostSession = loginAs("rmhost");
        Cookie targetSession = loginAs("rmtarget");
        Cookie outsideSession = loginAs("rmoutside");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/members/" + target;
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, outsideSession, csrf, "outside", null))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "host-target", null))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "body", "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, path).cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, "/tables/" + table + "/members/" + host,
                        hostSession, csrf, "self", null)).andExpect(status().isConflict());
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, targetSession, csrf, "non-host", null))
                .andExpect(status().isForbidden());
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, target, outsider);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, target);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, target);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, target);
        MvcResult blocked = mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("member_removal_unsettled");
        jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'proposer' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", bet);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, status, locked_at, remainder_rule)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'For fun?', '["Yes","No"]'::jsonb, 0, true,
                        now(), 'SETTLED', now(), 'proposer'),
                       (?, ?, ?, 'u2pick', '{}'::jsonb, 'Washed?', '["Yes","No"]'::jsonb, 100, false,
                        now(), 'WASHED', now(), 'proposer')
                """, UUID.randomUUID(), table, host, UUID.randomUUID(), table, host);
        jdbc.update("INSERT INTO chat_read_positions (table_id, user_id, last_read_order) VALUES (?, ?, 1)", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                String.class, bet, target)).isEqualTo("Yes");
        assertThat(jdbc.queryForObject("SELECT last_read_order FROM chat_read_positions WHERE table_id = ? AND user_id = ?",
                Long.class, table, target)).isEqualTo(1L);
        mvc.perform(get("/tables/" + table).cookie(targetSession)).andExpect(status().isNotFound());
        mvc.perform(get("/chat/" + table + "/messages").cookie(targetSession)).andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.member.removed.v1'
                    AND payload->>'removedUserId' = ?
                """, Integer.class, table.toString(), target.toString())).isEqualTo(1);
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove-frozen", null))
                .andExpect(status().isNoContent());
    }

    @Test
    void quickPickControlsRespectAccessRetriesAndPreserveCopies() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'qpmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'qpoutsider', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'QP', 'T', 'Q8R7QA', false, false), (?, 'Other', 'T', 'Q8R7QB', true, true)
                """, table, other);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, original, table, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, source_bet_id, source_table_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now(), ?, ?)
                """, copy, other, host, original, table);
        Cookie hostSession = loginAs("qphost");
        Cookie memberSession = loginAs("qpmember");
        Cookie outsiderSession = loginAs("qpoutsider");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/quick-picks";
        for (String invalid : List.of("{}", "{\"enabled\":null}", "{\"enabled\":1}", "{\"enabled\":true,\"x\":1}")) {
            mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "bad", invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, memberSession, csrf, "member", "{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, outsiderSession, csrf, "outside", "{\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/share", hostSession, csrf, "share", null))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "body", "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "enable", "{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled AND quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("Q8R7QA");
        jdbc.update("""
                INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)
                """, table, other, other, table);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path + "/share", hostSession, csrf, "stop", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? AND source_table_id = ?
                """, Integer.class, table, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? AND source_table_id = ?
                """, Integer.class, other, table)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.quickpicks.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, other.toString(), other.toString())).isEqualTo(1);
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/share", hostSession, csrf, "share", null))
                .andExpect(status().isNoContent());
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                other, table);
        MvcResult first = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isOk()).andReturn();
        String code1 = json.readTree(first.getResponse().getContentAsString()).path("code").asText();
        assertThat(code1).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}").isNotEqualTo("Q8R7QA");
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ?",
                Integer.class, table)).isZero();
        MvcResult second = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate2", null))
                .andExpect(status().isOk()).andReturn();
        String code2 = json.readTree(second.getResponse().getContentAsString()).path("code").asText();
        assertThat(code2).isNotEqualTo(code1);
        MvcResult replay = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("code").asText()).isEqualTo(code2);
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "rotate1", "{\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "disable", "{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT NOT quickpicks_enabled AND NOT quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? OR source_table_id = ?
                """, Integer.class, table, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_bet_id FROM bets WHERE id = ?", UUID.class, copy)).isEqualTo(original);
        int notices = jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table);
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "noop", "{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "enable", "{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(notices);
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled FROM tables WHERE id = ?", Boolean.class, table))
                .isFalse();
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentQuickPickDisableDisconnectsReciprocalSubscriptions() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstTable = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qpraceone', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'qpracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'First', 'T', 'Q8R8QA', true, true), (?, 'Second', 'T', 'Q8R8QB', true, true)
                """, firstTable, secondTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                firstTable, first, secondTable, second);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)",
                firstTable, secondTable, secondTable, firstTable);
        Jwt firstJwt = decoder.decode(loginAs("qpraceone").getValue());
        Jwt secondJwt = decoder.decode(loginAs("qpracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableQuickPicks.change(firstJwt, firstTable, TableQuickPickService.Action.DISABLE, "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableQuickPicks.change(secondJwt, secondTable, TableQuickPickService.Action.DISABLE, "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableQuickPickService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableQuickPickService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id IN (?, ?)
                        OR source_table_id IN (?, ?)
                    """, Integer.class, firstTable, secondTable, firstTable, secondTable)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void quickPickCodeCollisionDoesNotAbortTheTransaction() {
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'QP', 'T', 'Q8R6QA'), (?, 'Other', 'T', 'Q8R6QB')
                """, table, other);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(tableQuickPicks.tryRotate(table, "Q8R6QB")).isFalse();
            assertThat(tableQuickPicks.tryRotate(table, "Q8R6QC")).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("Q8R6QC");
    }

    @Test
    void quickPickAndMemberRemovalRollbackIfSessionExpiresWhileBlocked() throws Exception {
        UUID host = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qpexpiry', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'rmexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, target, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'QP', 'T', 'Q8R9QA', true, true), (?, 'Subscriber', 'T', 'Q8R9QB', true, true)
                """, table, subscriber);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, target);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriber, table);
        Jwt validated = decoder.decode(loginAs("qpexpiry").getValue());
        verifyMutationExpiry(validated,
                jwt -> tableQuickPicks.change(jwt, table, TableQuickPickService.Action.DISABLE, "qp-expiry"));
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled AND quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ?",
                Integer.class, table)).isEqualTo(1);
        verifyMutationExpiry(validated, jwt -> tableMemberRemovals.remove(jwt, table, target, "rm-expiry"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id IN (?, ?)",
                Integer.class, table.toString(), subscriber.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
    }

    private void verifyMutationExpiry(Jwt validated, java.util.function.Function<Jwt, ?> mutation)
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder quickPickRequest(
            HttpMethod method, String path, Cookie session, Csrf csrf, String key, String body) {
        var builder = request(method, path).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return builder;
    }

    @Test
    void tableSettingsRespectAccessRetriesAndExistingBets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'settingshost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingsmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingsoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Settings', 'T', 'Y5R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, member);
        Cookie hostSession = loginAs("settingshost");
        Cookie memberSession = loginAs("settingsmember");
        Cookie outsideSession = loginAs("settingsoutside");
        Csrf csrf = csrf();
        String both = "{\"hostsOnlyBets\":true,\"u2pickEnabled\":false}";
        mvc.perform(patch("/tables/" + table + "/settings").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table + "/settings").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"hostsOnlyBets\":null}", "{\"hostsOnlyBets\":\"true\"}",
                "{\"u2pickEnabled\":0}", "{\"unknown\":true}", "{\"hostsOnlyBets\":true,\"unknown\":false}")) {
            mvc.perform(settingsPatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(settingsPatch(table, memberSession, csrf, "member", both)).andExpect(status().isForbidden());
        mvc.perform(settingsPatch(table, outsideSession, csrf, "outsider", both)).andExpect(status().isNotFound());
        mvc.perform(settingsPatch(UUID.randomUUID(), hostSession, csrf, "missing", both)).andExpect(status().isNotFound());
        MvcResult first = mvc.perform(settingsPatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(first.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(view)).isEmpty();
        assertThat(view.path("hostsOnlyBets").asBoolean()).isTrue();
        assertThat(view.path("u2pickEnabled").asBoolean()).isFalse();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.settings.changed.v1'
                """, Integer.class, table.toString())).isEqualTo(1);
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", both)).andExpect(status().isOk());
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(settingsPatch(table, hostSession, csrf, "second", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isOk());
        MvcResult replay = mvc.perform(settingsPatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("hostsOnlyBets").asBoolean()).isFalse();
        mvc.perform(settingsPatch(table, hostSession, csrf, "noop", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(3);
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", both)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentTableSettingsUpdatesPreserveBothChanges() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'setraceone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'setracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Y5R7QB')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("setraceone").getValue());
        Jwt secondJwt = decoder.decode(loginAs("setracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableSettings.update(firstJwt, table, new TableSettingsService.Changes(true, null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableSettings.update(secondJwt, table, new TableSettingsService.Changes(null, false), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(TableSettingsService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(TableSettingsService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT hosts_only_bets AND NOT u2pick_enabled FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableSettingsExpiryRollsBackChangesAndEvents() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'setexpiryhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'Y5R7QC')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("setexpiryhost").getValue());
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
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableSettings.update(expiring, table,
                    new TableSettingsService.Changes(true, false), "expiry"));
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
            assertThat(jdbc.queryForObject("SELECT NOT hosts_only_bets AND u2pick_enabled FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table + "/settings").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void remainderChangesRespectActiveBetsAuthorizationAndRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'remaindhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'remaindmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'remaindoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Remainder', 'T', 'Z9R7QA')", table);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Other', 'T', 'Y4R7QB')", otherTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Other?', '["Yes","No"]'::jsonb, 100, false, now())
                """, UUID.randomUUID(), otherTable, host);
        Cookie hostSession = loginAs("remaindhost");
        Cookie memberSession = loginAs("remaindmember");
        Cookie outsideSession = loginAs("remaindoutside");
        Jwt validated = decoder.decode(hostSession.getValue());
        Csrf csrf = csrf();
        String proposer = "{\"rule\":\"proposer\"}";
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON).content(proposer))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(proposer)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(proposer)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"rule\":null}", "{\"rule\":true}", "{\"rule\":\"bad\"}",
                "{\"rule\":\"host\",\"extra\":1}")) {
            mvc.perform(remainderPatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(remainderPatch(table, memberSession, csrf, "member", proposer)).andExpect(status().isForbidden());
        mvc.perform(remainderPatch(table, outsideSession, csrf, "outside", proposer)).andExpect(status().isNotFound());
        mvc.perform(remainderPatch(UUID.randomUUID(), hostSession, csrf, "missing", proposer))
                .andExpect(status().isNotFound());
        MvcResult first = mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(remainderPatch(table, hostSession, csrf, "second", "{\"rule\":\"random\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", "{\"rule\":\"random\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("random");
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "noop", "{\"rule\":\"random\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "blocked", "{\"rule\":\"earliest\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'proposer' WHERE id = ?
                """, bet);
        mvc.perform(remainderPatch(table, hostSession, csrf, "blocked", "{\"rule\":\"earliest\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "host-rule", "{\"rule\":\"host\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT remainder_rule FROM bets WHERE id = ?", String.class, bet))
                .isEqualTo("proposer");
        JsonNode window = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(window.path("items").size()).isEqualTo(4);
        assertThat(window.path("items").get(2).path("kind").asText()).isEqualTo("system");
        assertThat(window.path("items").get(2).path("text").asText()).isEqualTo("Leftover cents set to: Earliest pick");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ?
                  AND event_type IN ('table.remainder.changed.v1', 'chat.message.created.v1')
                """, Integer.class, table.toString())).isEqualTo(8);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(remainderPatch(table, memberSession, csrf, "archived", proposer)).andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        assertThat(tableRemainder.set(validated, table, TableRemainderService.Rule.PROPOSER, "first"))
                .isEqualTo(TableRemainderService.Outcome.UNAUTHORIZED);
    }

    @Test
    void remainderChangeWaitsForActiveBetAndRollsBackOnExpiry() throws Exception {
        UUID host = UUID.randomUUID();
        UUID proposer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'remaindrace', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'remaindpropose', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, proposer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Z9R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("remaindrace").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var outboxLocked = new java.util.concurrent.CountDownLatch(1);
        var outboxRelease = new java.util.concurrent.CountDownLatch(1);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("""
                        INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                          wager_cents, is_4fun, locks_at)
                        VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                        """, bet, table, proposer);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var change = executor.submit(() -> tableRemainder.set(validated, table, TableRemainderService.Rule.RANDOM, "race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT remainder_rule FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TableRemainderService.ActiveBet.class);
            assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                    .isEqualTo("host");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                    .isZero();
            jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'random' WHERE id = ?", bet);
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                outboxLocked.countDown();
                try {
                    if (!outboxRelease.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(outboxLocked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableRemainder.set(expiring, table, TableRemainderService.Rule.RANDOM, "expiry"));
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            outboxRelease.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                    .isEqualTo("host");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            outboxRelease.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder remainderPatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table + "/remainder").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void hostChangesPreserveActiveHostCancelInvitesAndAttributeChatSafely() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostactor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hosttarget', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'hostoutsider', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinvitee', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, target, hash, frozen, hash, outsider, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Hosts', 'T', 'H4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_text, can_react)
                VALUES (?, ?, 'HOST', true, true), (?, ?, 'MEMBER', false, false), (?, ?, 'MEMBER', true, true)
                """, table, actor, table, target, table, frozen);
        Cookie actorSession = loginAs("hostactor");
        Cookie targetSession = loginAs("hosttarget");
        Cookie outsideSession = loginAs("hostoutsider");
        Jwt validated = decoder.decode(actorSession.getValue());
        Csrf csrf = csrf();
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous")).andExpect(status().isUnauthorized());
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(actorSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(actorSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "bad-body").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, targetSession, csrf, "member"))
                .andExpect(status().isForbidden());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, outsideSession, csrf, "outsider"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.PUT, table, outsider, actorSession, csrf, "not-member"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.PUT, table, UUID.randomUUID(), actorSession, csrf, "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, actor))
                .isZero();
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, actorSession, csrf, "already-host"))
                .andExpect(status().isNoContent());
        MvcResult promoted = mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(promoted.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("""
                SELECT role = 'HOST' AND can_bet AND can_text AND can_react AND can_send_gifs
                FROM table_members WHERE table_id = ? AND user_id = ?
                """, Boolean.class, table, target)).isTrue();
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, target);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, target, recipient);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents,
                                  is_4fun, locks_at, status, locked_at, remainder_rule, remainder_host_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now(), 'PENDING', now(), 'host', ?)
                """, bet, table, actor, target);
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "demote"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, bet))
                .isEqualTo(target);
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                SELECT role = 'MEMBER' AND can_bet AND can_text AND can_react AND can_send_gifs
                FROM table_members WHERE table_id = ? AND user_id = ?
                """, Boolean.class, table, target)).isTrue();
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "already-member"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote-again"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        mvc.perform(hostRequest(HttpMethod.PUT, table, frozen, actorSession, csrf, "promote-frozen"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "demote-again"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", frozen);
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isForbidden());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ?
                  AND event_type IN ('table.host.changed.v1', 'chat.message.created.v1')
                """, Integer.class, table.toString())).isEqualTo(12);
        jdbc.update("UPDATE users SET username = 'hostrenamed' WHERE id = ?", target);
        JsonNode renamed = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(actorSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(renamed.path("items").get(0).path("text").asText()).isEqualTo("hostrenamed is now a host");
        assertThat(renamed.path("items").get(1).path("text").asText()).isEqualTo("hostrenamed is no longer a host");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM chat_messages WHERE table_id = ? AND system_subject_id = ? AND text LIKE '%hosttarget%'
                """, Integer.class, table, target)).isZero();
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, target);
        JsonNode deleted = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(actorSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(deleted.path("items").get(0).path("text").asText()).isEqualTo("Deleted user is now a host");
        assertThat(deleted.path("items").get(1).path("text").asText()).isEqualTo("Deleted user is no longer a host");
        assertThat(deleted.toString()).doesNotContain("hosttarget", "hostrenamed");
        Cookie formerFrozenSession = loginAs("hostfrozen");
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, formerFrozenSession, csrf, "deleted-target"))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_subject_id)
                VALUES (?, ?, 'system', 'Invalid', ?)
                """, UUID.randomUUID(), table, actor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', 'hosttarget is now a host', 'host_promoted', ?)
                """, UUID.randomUUID(), table, actor)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, formerFrozenSession, csrf, "archived"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", actor);
        assertThat(tableHosts.set(validated, table, actor, true, "already-host"))
                .isEqualTo(TableHostService.Outcome.UNAUTHORIZED);
    }

    @Test
    void concurrentHostStepDownsRetainAnActiveHost() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'H4R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("hostracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("hostraceb").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return tableHosts.set(firstJwt, table, first, false, "step-down");
                } catch (TableHostService.LastActiveHost exception) {
                    return TableHostService.Outcome.CONFLICT;
                }
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return tableHosts.set(secondJwt, table, second, false, "step-down");
                } catch (TableHostService.LastActiveHost exception) {
                    return TableHostService.Outcome.CONFLICT;
                }
            });
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(TableHostService.Outcome.UPDATED, TableHostService.Outcome.CONFLICT);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM table_members m JOIN users u ON u.id = m.user_id
                    WHERE m.table_id = ? AND m.role = 'HOST' AND u.status = 'ACTIVE'
                    """, Integer.class, table)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostDemotionAndInvitationAcceptanceSerialize() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostinvitea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinviteb', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinvitec', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, sender, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'H4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, sender);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, sender);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, sender, recipient);
        Jwt actorJwt = decoder.decode(loginAs("hostinvitea").getValue());
        Jwt recipientJwt = decoder.decode(loginAs("hostinvitec").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var demote = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableHosts.set(actorJwt, table, sender, false, "demote-race");
            });
            var accept = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitationResponses.respond(recipientJwt, invite, true, "accept-race");
            });
            start.countDown();
            assertThat(demote.get(10, TimeUnit.SECONDS)).isEqualTo(TableHostService.Outcome.UPDATED);
            InvitationResponseService.Outcome outcome = accept.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isIn(InvitationResponseService.Outcome.UPDATED, InvitationResponseService.Outcome.NOT_FOUND,
                    InvitationResponseService.Outcome.STALE);
            boolean accepted = outcome == InvitationResponseService.Outcome.UPDATED;
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo(accepted ? "ACCEPTED" : "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                    Integer.class, table, recipient)).isEqualTo(accepted ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostStepDownObservesCoordinatedConcurrentFreeze() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostfreezea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostfreezeb', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Freeze', 'T', 'H4F9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, other);
        Jwt actorJwt = decoder.decode(loginAs("hostfreezea").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var freeze = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, other);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var stepDown = executor.submit(() -> tableHosts.set(actorJwt, table, actor, false, "freeze-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            freeze.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stepDown.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TableHostService.LastActiveHost.class);
            assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                    String.class, table, actor)).isEqualTo("HOST");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostChangeExpiryRollsBackRoleInviteMessageAndClaim() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostexpirea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostexpireb', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostexpirec', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, target, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'H4X9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, target);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, target, recipient);
        Jwt validated = decoder.decode(loginAs("hostexpirea").getValue());
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
                    .subject(actor.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableHosts.set(expiring, table, target, false, "expiry"));
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
            assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                    String.class, table, target)).isEqualTo("HOST");
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, actor)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableCreationAndMemberRequestsRespectPreferencesAndReplay() throws Exception {
        UUID host = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        UUID closed = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'createhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfriend', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfollower', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createclosed', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'createblocked', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, friend, hash, follower, hash, closed, hash, frozen, hash, blocked, hash);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, friend, host, host, friend, follower, host, closed, host, frozen, host, blocked, host);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocked, host);
        jdbc.update("INSERT INTO invite_preferences (user_id, following) VALUES (?, 'closed')", closed);
        Cookie session = loginAs("createhost");
        Csrf csrf = csrf();
        var input = json.createObjectNode().put("name", "  Weekend  ").put("emoji", "🏈");
        var ids = input.putArray("memberIds");
        for (UUID id : List.of(friend, follower, closed, frozen, blocked, missing, host)) ids.add(id.toString());
        String body = json.writeValueAsString(input);
        MvcResult created = mvc.perform(tablePost("/tables", session, csrf, "create-table", body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("TableCreationResult").validate(response)).isEmpty();
        UUID table = UUID.fromString(response.path("table").path("id").asText());
        assertThat(response.path("table").path("name").asText()).isEqualTo("Weekend");
        assertThat(response.path("outcomes").findValuesAsText("outcome"))
                .containsExactly("added", "invited", "not_added", "not_added", "not_added", "not_added", "not_added");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ?",
                Integer.class, table)).isEqualTo(2);
        UUID invite = jdbc.queryForObject("SELECT id FROM table_invites WHERE table_id = ? AND user_id = ?",
                UUID.class, table, follower);
        assertThat(invite).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND text ILIKE '%invit%'",
                Integer.class, table)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_type = 'user' AND aggregate_id = ?
                    AND event_type = 'table.invitation.created.v1'
                """, Integer.class, follower.toString())).isEqualTo(1);
        mvc.perform(invitationResponse(invite, loginAs("createfollower"), csrf, "accept-created-invite", true))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE tables SET name = 'Renamed' WHERE id = ?", table);
        MvcResult replay = mvc.perform(tablePost("/tables", session, csrf, "create-table", body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode replayed = json.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.path("table").path("name").asText()).isEqualTo("Renamed");
        assertThat(replayed.path("table").path("memberIds").size()).isEqualTo(3);
        assertThat(replayed.path("outcomes")).isEqualTo(response.path("outcomes"));
        mvc.perform(tablePost("/tables", session, csrf, "create-table", body.replace("Weekend", "Other")))
                .andExpect(status().isConflict());
        mvc.perform(tablePost("/tables", session, csrf, "bad", "{\"name\":\"X\",\"emoji\":\"🏈\",\"memberIds\":[\""
                + friend + "\",\"" + friend + "\"]}")).andExpect(status().isBadRequest());
        mvc.perform(tablePost("/tables", session, csrf, "bad", "{\"name\":\"X\",\"emoji\":\"no\"}"))
                .andExpect(status().isBadRequest());
        var oversized = json.createObjectNode().put("name", "X").put("emoji", "🏈");
        var tooMany = oversized.putArray("memberIds");
        for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID().toString());
        mvc.perform(tablePost("/tables", session, csrf, "bad", json.writeValueAsString(oversized)))
                .andExpect(status().isBadRequest());
        tooMany.remove(100);
        MvcResult boundary = mvc.perform(tablePost("/tables", session, csrf, "limit", json.writeValueAsString(oversized)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(boundary.getResponse().getContentAsString()).path("outcomes").size()).isEqualTo(100);

        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, friend);
        var add = json.createObjectNode();
        add.putArray("userIds").add(friend.toString()).add(closed.toString());
        String addBody = json.writeValueAsString(add);
        String path = "/tables/" + table + "/members";
        MvcResult firstAdd = mvc.perform(tablePost(path, session, csrf, "add-members", addBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode addResponse = json.readTree(firstAdd.getResponse().getContentAsString());
        for (JsonNode member : addResponse) {
            assertThat(OpenApiSchemas.load("MemberRequestOutcome").validate(member)).isEmpty();
        }
        assertThat(addResponse.findValuesAsText("outcome"))
                .containsExactly("added", "not_added");
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, friend);
        MvcResult addReplay = mvc.perform(tablePost(path, session, csrf, "add-members", addBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(addReplay.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(firstAdd.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, friend)).isZero();
        mvc.perform(tablePost(path, session, csrf, "add-members", "{\"userIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(tablePost(path, loginAs("createfollower"), csrf, "not-host", addBody))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, follower);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(tablePost(path, session, csrf, "add-members", addBody)).andExpect(status().isNotFound());
        mvc.perform(tablePost("/tables", session, csrf, "create-table", body)).andExpect(status().isNotFound());
    }

    @Test
    void concurrentTableCreationRetriesCreateOnlyOneTable() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'createretry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("createretry").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.create(jwt, "same-create", "Retry", "🏈", List.of());
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.create(jwt, "same-create", "Retry", "🏈", List.of());
            });
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertThat(one.outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(two.outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(one.tableId()).isEqualTo(two.tableId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE user_id = ?",
                    Integer.class, user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, one.tableId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentTableInvitationAndBlockCannotLeavePendingInvitation() throws Exception {
        UUID host = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'create_racehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'create_racepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'T9S7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        Jwt hostJwt = decoder.decode(loginAs("create_racehost").getValue());
        Jwt recipientJwt = decoder.decode(loginAs("create_racepeer").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var add = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.add(hostJwt, table, "race-add", List.of(recipient));
            });
            var block = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(recipientJwt, host, "race-block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            assertThat(add.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(block.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM table_invites WHERE table_id = ? AND user_id = ? AND status = 'PENDING'
                    """, Integer.class, table, recipient)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableMemberRequestRollsBackWhenSessionExpiresWhileWaiting() throws Exception {
        UUID host = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'create_expire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'create_expeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Wait', 'T', 'T9S8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        Jwt validated = decoder.decode(loginAs("create_expire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
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
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var add = executor.submit(() -> tableCreation.add(expiring, table, "expired-add", List.of(recipient)));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %FOR UPDATE%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> add.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_invites WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tablePost(
            String path, Cookie session, Csrf csrf, String key, String body) {
        return post(path).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder hostRequest(
            HttpMethod method, UUID table, UUID target, Cookie session, Csrf csrf, String key) {
        return request(method, "/tables/" + table + "/hosts/" + target).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }

    private String feedbackBody(String kind, String text, String link) throws Exception {
        var body = json.createObjectNode().put("kind", kind).put("text", text);
        if (link != null) body.put("link", link);
        return json.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder feedbackPost(
            Cookie session, Csrf csrf, String key, String body) {
        return post("/feedback").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invitePreferencesPut(
            Cookie session, Csrf csrf, String key, String body) {
        return put("/me/invite-preferences").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder debtCapPut(
            Cookie session, Csrf csrf, String key, String body) {
        return put("/me/debt-cap").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Cookie loginAs(String username) throws Exception {
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

    private Csrf csrf() throws Exception {
        MvcResult result = mvc.perform(get("/auth/csrf")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"),
                json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }

    record Csrf(Cookie cookie, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminProbeConfiguration {
        @Bean
        AdminProbeController adminProbeController() {
            return new AdminProbeController();
        }

        @Bean
        BetMutationController testBetMutationController(U2PickProposalService proposals, BetPickService picks,
                U2PickResolutionService resolutions, BetReadController reads) {
            return new BetMutationController(proposals, picks, resolutions, reads);
        }

        @Bean
        PointsSettlementController testPointsSettlementController(TablePointsSettlementService settlements) {
            return new PointsSettlementController(settlements);
        }

        @Bean
        SettlementReceiptController testSettlementReceiptController(SettlementReceiptService receipts) {
            return new SettlementReceiptController(receipts);
        }

        @Bean
        SettlementGroupController testSettlementGroupController(SettlementGroupService groups, SettlementReviewService reviews) {
            return new SettlementGroupController(groups, reviews);
        }
    }

    @RestController
    static class AdminProbeController {
        @RequestMapping(path = "/admin/security-probe", method = {RequestMethod.GET, RequestMethod.POST})
        ResponseEntity<Void> probe() {
            return ResponseEntity.noContent().build();
        }
    }
}
