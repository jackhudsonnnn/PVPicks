package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DevSeedTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void seedIsOneTimeAndDoesNotRestoreChangedOrRemovedData() throws IOException {
        JdbcTemplate jdbc = database();
        insertUsers(jdbc);
        seed(jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tables", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bets
                WHERE config->>'question' = question AND config->'options' = choices
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bet_participants bp JOIN users u ON u.id = bp.user_id
                WHERE u.username = 'newbie'::citext
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE choice = 'Pass'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bet_participants bp
                LEFT JOIN picks p ON p.bet_id = bp.bet_id AND p.user_id = bp.user_id
                WHERE p.user_id IS NULL
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bets b
                JOIN bet_participants bp ON bp.bet_id = b.id AND bp.user_id = b.proposer_id
                WHERE b.question = 'What will the next play be?'
                """, Integer.class)).isZero();

        jdbc.update("UPDATE tables SET name = 'Changed locally' WHERE quickpick_code = 'DEMA2A'");
        jdbc.update("DELETE FROM picks WHERE choice = 'Pass'");
        seed(jdbc);
        assertThat(jdbc.queryForObject("SELECT name FROM tables WHERE quickpick_code = 'DEMA2A'", String.class))
                .isEqualTo("Changed locally");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE choice = 'Pass'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM p2picks_dev_seed.runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingAccountsAndCodeConflictsRollBackWithoutChangingExistingData() throws IOException {
        JdbcTemplate jdbc = database();
        assertThatThrownBy(() -> seed(jdbc)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tables", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT to_regnamespace('p2picks_dev_seed') IS NULL", Boolean.class)).isTrue();

        insertUsers(jdbc);
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Existing', 'T', 'DEMA2A')",
                existing);
        assertThatThrownBy(() -> seed(jdbc)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tables", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM tables WHERE id = ?", String.class, existing))
                .isEqualTo("Existing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT to_regnamespace('p2picks_dev_seed') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void concurrentSeedRunsDoNotDuplicateSamples() throws Exception {
        JdbcTemplate jdbc = database();
        insertUsers(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                seedAfter(start, jdbc);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                seedAfter(start, jdbc);
                return null;
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tables", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bets", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM p2picks_dev_seed.runs", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void seedAfter(CountDownLatch start, JdbcTemplate jdbc) throws IOException, InterruptedException {
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start seed");
        }
        seed(jdbc);
    }

    private static void seed(JdbcTemplate jdbc) throws IOException {
        try (var input = Objects.requireNonNull(DevSeedTest.class.getResourceAsStream("/dev-seed.sql"))) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static JdbcTemplate database() {
        String database = "seed_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword()).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    private static void insertUsers(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'newbie', 1, 'test', 'PLAYER', 'ACTIVE'),
                       (?, 'intermediate', 2, 'test', 'PLAYER', 'ACTIVE'),
                       (?, 'pro', 3, 'test', 'PLAYER', 'ACTIVE'),
                       (?, 'admin', 4, 'test', 'ADMIN', 'ACTIVE')
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
