package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.bets.U2PickDefinition;
import com.p2picks.app.bets.U2PickProposalService;
import com.p2picks.app.security.PasswordlessSignIn;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The bots against a real demo world: their own database (seeded at startup, as an empty dev database is), the
 * demo on, and a clock the test moves. The shared test database is never touched.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(DemoBotsIntegrationTest.TestClock.class)
class DemoBotsIntegrationTest {
    private static final String DATABASE = "demo_bots";

    @DynamicPropertySource
    static void demoDatabase(DynamicPropertyRegistry registry) throws SQLException {
        IntegrationTestSupport.registerServices(registry);
        var postgres = IntegrationTestSupport.postgres;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1"));
        registry.add("pvpicks.demo.enabled", () -> "true");
        registry.add("pvpicks.demo.bots.initial-delay", () -> "PT24H");
        registry.add("pvpicks.demo.bots.tick", () -> "PT24H");
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    static class MovableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DemoBots bots;
    @Autowired
    MovableClock clock;
    @Autowired
    PasswordlessSignIn signIn;
    @Autowired
    U2PickProposalService u2picks;
    @Autowired
    ObjectMapper json;

    @Test
    void demoUsersJoinTheProsBetsAsThemselves() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class))
                .as("startup seeded the empty demo database").isEqualTo(DemoRoster.SIZE + 4);
        UUID gameNight = jdbc.queryForObject("SELECT id FROM tables WHERE name = 'Game Night'", UUID.class);
        List<UUID> demoMembers = jdbc.queryForList("""
                SELECT user_id FROM table_members WHERE table_id = ? AND user_id <> ALL (?::uuid[])
                """, UUID.class, gameNight, "{" + DemoWorld.PRO + "," + DemoWorld.INTERMEDIATE + "}");
        // A recent moment when every demo member of Game Night is around (online status is stable per slot).
        Instant moment = Instant.now().minus(BotMind.ONLINE_SLOT);
        while (!allOnline(demoMembers, moment)) moment = moment.minus(BotMind.ONLINE_SLOT);
        clock.now = moment;
        bots.forget();
        bots.reseed(7);

        Jwt pro = signIn.session(DemoWorld.PRO);
        List<UUID> bets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var proposal = new U2PickProposalService.Proposal(new U2PickDefinition("Question " + i + "?",
                    List.of("Yes", "No")), json.createObjectNode(), 100, 600, false, null);
            bets.add(u2picks.create(pro, gameNight, proposal, UUID.randomUUID().toString()).betId());
        }

        // A seeded world gives the bots a backlog (receipts, chat); they work through it 50 actions a tick.
        bots.tick();
        for (int i = 0; i < 12; i++) {
            clock.now = clock.now.plus(Duration.ofSeconds(15));
            bots.tick();
        }

        List<String> picks = jdbc.queryForList("""
                SELECT choice FROM picks WHERE bet_id = ANY (?::uuid[]) AND user_id = ANY (?::uuid[])
                """, String.class, array(bets), array(demoMembers));
        assertThat(picks).as("demo members joined through the pick service; bots: %s", bots.status()).isNotEmpty().allMatch(
                choice -> choice.equals("Yes") || choice.equals("No"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_sessions WHERE user_id = ANY (?::uuid[])
                """, Integer.class, array(demoMembers))).as("they act with real sessions").isPositive();
        @SuppressWarnings("unchecked")
        var done = (java.util.Map<String, Integer>) bots.status().get("done");
        assertThat(done).containsKey("pick updated");
    }

    @Test
    void startupSeedsTheMainAccountsHistoriesConsistently() {
        int proTickets = count("""
                SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id
                WHERE p.user_id = ? AND b.status IN ('SETTLED', 'WASHED')
                """, DemoWorld.PRO);
        assertThat(proTickets).as("a dense history").isBetween(200, 400);
        assertThat(count("""
                SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id WHERE p.user_id = ? AND b.status = 'PENDING'
                """, DemoWorld.PRO)).isEqualTo(DemoHistory.PRO_PENDING);
        assertThat(count("""
                SELECT count(DISTINCT (created_at AT TIME ZONE 'UTC')::date) FROM bets b
                JOIN picks p ON p.bet_id = b.id WHERE p.user_id = ?
                """, DemoWorld.PRO)).as("spread over the last 90 days").isGreaterThan(60);
        assertThat(count("""
                SELECT count(*) FROM settlement_lines WHERE (payer_id = ? OR payee_id = ?) AND status = 'paid'
                """, DemoWorld.PRO, DemoWorld.PRO)).isPositive();
        assertThat(count("""
                SELECT count(*) FROM settlement_lines WHERE (payer_id = ? OR payee_id = ?) AND status = 'unpaid'
                """, DemoWorld.PRO, DemoWorld.PRO)).as("some settle-ups still open").isPositive();

        assertThat(count("""
                SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id
                WHERE p.user_id = ? AND b.status IN ('SETTLED', 'WASHED')
                """, DemoWorld.INTERMEDIATE)).isBetween(1, DemoHistory.INTERMEDIATE_TICKETS);
        assertThat(count("""
                SELECT count(*) FROM picks p JOIN bets b ON b.id = p.bet_id
                WHERE p.user_id = ? AND b.status IN ('ACTIVE', 'PENDING')
                """, DemoWorld.INTERMEDIATE)).isZero();
        assertThat(count("""
                SELECT count(*) FROM settlement_lines WHERE (payer_id = ? OR payee_id = ?) AND status <> 'paid'
                """, DemoWorld.INTERMEDIATE, DemoWorld.INTERMEDIATE)).isZero();
        assertThat(count("SELECT count(*) FROM picks WHERE user_id = ?", DemoWorld.NEWBIE)).isZero();

        assertThat(count("SELECT count(*) FROM reports")).as("open reports for the admin").isGreaterThanOrEqualTo(3);
        assertThat(count("""
                SELECT count(*) FROM (SELECT bet_id FROM ledger_entries GROUP BY bet_id HAVING sum(delta_cents) <> 0) x
                """)).as("every bet's ledger is zero-sum").isZero();
        assertThat(count("""
                SELECT count(*) FROM pg_trigger WHERE tgname IN ('chat_messages_position_immutable',
                    'settlement_lines_identity_immutable') AND tgenabled = 'O'
                """)).as("guards back on after backdating").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM games WHERE provider_id LIKE 'DEV-demo-%'"))
                .as("the looping demo games are on the board").isPositive();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static boolean allOnline(List<UUID> ids, Instant at) {
        return ids.stream().allMatch(id -> BotMind.online(id, profile(id), at));
    }

    private static DemoProfile profile(UUID id) {
        return DemoRoster.users().stream().filter(user -> user.id().equals(id)).findFirst().orElseThrow().profile();
    }

    private static String array(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }
}
