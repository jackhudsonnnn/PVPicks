package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.settlement.DebtQuery;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class BetLockWorkerTest {
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static BetLockWorker worker;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        worker = new BetLockWorker(jdbc,
                new BetLockService(jdbc, new DebtQuery(jdbc), new Outbox(jdbc, new ObjectMapper())), manager);
    }

    @Test
    void overdueScanContinuesPastFailuresAcrossPagesAndRetriesOnRecovery() throws Exception {
        UUID host = user("workerhost", "ACTIVE");
        UUID frozen = user("workerfrozen", "FROZEN");
        UUID table = table("W3A3QA");
        UUID brokenTable = table("W3A4QA");
        member(table, host, "HOST");
        member(brokenTable, frozen, "MEMBER");
        UUID broken = bet(brokenTable, frozen, "u2pick", "3 minutes");
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", broken, frozen);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", broken, frozen);
        UUID sports = bet(table, host, "either_or", "2 minutes");
        for (int i = 0; i < 101; i++) bet(table, host, "u2pick", "1 minute");
        UUID future = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Future?', '["Yes","No"]', 100, false,
                        clock_timestamp() + interval '1 hour')
                """, future, table, host);
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE table_id = ? AND status = 'PENDING'",
                Integer.class, table)).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, broken)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, broken)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, sports)).isEqualTo("ACTIVE");
        var locks = new BetLockService(jdbc, new DebtQuery(jdbc), new Outbox(jdbc, new ObjectMapper()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> locks.lock(sports, Set.of("u2pick"))))
                .isInstanceOf(BetLockService.UnsupportedMode.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, future)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM bets WHERE table_id = ? AND status = 'PENDING'
                  AND remainder_rule = 'host' AND remainder_host_id = ?
                """, Integer.class, table, host)).isEqualTo(101);
        member(brokenTable, host, "HOST");
        worker.poll();
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, broken)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, broken)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, broken))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, broken)).isEqualTo(host);
        jdbc.update("UPDATE bets SET mode = 'u2pick', game_id = NULL WHERE id = ?", sports);
        worker.poll();
        UUID race = bet(table, host, "u2pick", "1 minute");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                worker.poll();
                return null;
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                worker.poll();
                return null;
            });
            start.countDown();
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'
                """, Integer.class, race.toString())).isEqualTo(1);
    }

    @Test
    void participantRaceIsRetriedOnTheNextScan() throws Exception {
        UUID host = user("workerhost2", "ACTIVE");
        UUID picker = user("workerpicker", "ACTIVE");
        UUID table = table("W3B3QA");
        member(table, host, "HOST");
        member(table, picker, "MEMBER");
        UUID bet = bet(table, host, "u2pick", "1 minute");
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, picker);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, picker);
                jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, picker);
                inserted.countDown();
                await(release);
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var scan = executor.submit(worker::poll);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT remainder_rule FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            scan.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
            worker.poll();
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void lockTimeoutLeavesOtherDueBetsProcessableAndRetriesLater() throws Exception {
        UUID host = user("workerhost3", "ACTIVE");
        UUID blockedTable = table("W3C3QA");
        UUID otherTable = table("W3C4QA");
        member(blockedTable, host, "HOST");
        member(otherTable, host, "HOST");
        UUID blocked = bet(blockedTable, host, "u2pick", "2 minutes");
        UUID available = bet(otherTable, host, "u2pick", "1 minute");
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, blockedTable);
                held.countDown();
                await(release);
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            worker.poll();
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, blocked)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, available)).isEqualTo("PENDING");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            worker.poll();
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, blocked)).isEqualTo("PENDING");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void scanErrorsAreNotReportedAsAnEmptyScan() {
        var missing = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unavailable",
                "test", "test");
        var failingJdbc = new JdbcTemplate(missing);
        var failing = new BetLockWorker(failingJdbc,
                new BetLockService(failingJdbc, new DebtQuery(failingJdbc), new Outbox(failingJdbc, new ObjectMapper())),
                new DataSourceTransactionManager(missing));
        assertThatThrownBy(failing::poll).isInstanceOf(DataAccessException.class);
    }

    private UUID user(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'hash', 'PLAYER', ?)
                """, id, name, status);
        return id;
    }

    private UUID table(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Worker', 'T', ?)", id, code);
        return id;
    }

    private void member(UUID table, UUID user, String role) {
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, ?)", table, user, role);
    }

    private UUID bet(UUID table, UUID proposer, String mode, String overdue) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, game_id, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'u2pick' THEN NULL ELSE 'game' END, '{}'::jsonb,
                        'Question?', '["Yes","No"]', 100, false, clock_timestamp() - ?::interval)
                """, id, table, proposer, mode, mode, overdue);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
