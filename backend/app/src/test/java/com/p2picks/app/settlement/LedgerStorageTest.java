package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class LedgerStorageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void commitsBalancedEntriesAndAppendOnlyCompensation() {
        Fixture fixture = fixture("34BCDE");
        UUID entryId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            entry(entryId, fixture.bet(), fixture.table(), fixture.first(), 100);
            entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.second(), -100);
        });
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?",
                        Long.class, fixture.bet())).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET delta_cents = 1 WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries"))
                .isInstanceOf(DataIntegrityViolationException.class);

        transaction.executeWithoutResult(status -> {
            entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.first(), -100);
            entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.second(), 100);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?",
                        Integer.class, fixture.bet())).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ? AND user_id = ?
                """, Long.class, fixture.bet(), fixture.first())).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE bets SET is_4fun = true WHERE id = ?", fixture.bet()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnbalancedCommitAndRollsBackItsOutboxEvent() {
        Fixture fixture = fixture("45BCDE");
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.first(), 100);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'bet', ?, 'example.v1', '{}'::jsonb)
                    """, eventId, fixture.bet().toString());
        })).hasStackTraceContaining("Ledger entries must sum to zero");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?",
                        Integer.class, fixture.bet())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?",
                        Integer.class, eventId)).isZero();
    }

    @Test
    void rejectsFourFunAndWrongTableEntries() {
        Fixture fixture = fixture("56BCDE");
        UUID otherTable = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Other', 'T', '67BCDE')",
                otherTable);
        assertThatThrownBy(() -> entry(
                        UUID.randomUUID(), fixture.funBet(), fixture.table(), fixture.first(), 100))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> entry(
                        UUID.randomUUID(), fixture.bet(), otherTable, fixture.first(), 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void serializesConcurrentWritersForTheSameBet() throws Exception {
        Fixture fixture = fixture("78BCDE");
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondInserted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.first(), 100);
                firstInserted.countDown();
                await(releaseFirst);
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.second(), -100);
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.first(), 50);
                secondInserted.countDown();
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.second(), -50);
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondInserted.await(500, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?",
                            Long.class, fixture.bet())).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentUnbalancedTransactionsCannotOffsetEachOther() throws Exception {
        Fixture fixture = fixture("89BCDE");
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.first(), 100);
                firstInserted.countDown();
                await(releaseFirst);
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                entry(UUID.randomUUID(), fixture.bet(), fixture.table(), fixture.second(), -100);
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
                    .hasStackTraceContaining("Ledger entries must sum to zero");
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasStackTraceContaining("Ledger entries must sum to zero");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?",
                            Integer.class, fixture.bet())).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void entry(UUID id, UUID bet, UUID table, UUID user, long delta) {
        jdbc.update("""
                INSERT INTO ledger_entries (id, bet_id, table_id, user_id, delta_cents)
                VALUES (?, ?, ?, ?, ?)
                """, id, bet, table, user, delta);
    }

    private static Fixture fixture(String code) {
        UUID table = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        UUID funBet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, ?, 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, first, "u" + first.toString().substring(0, 12).replace("-", ""),
                second, "u" + second.toString().substring(0, 12).replace("-", ""));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Ledger', 'T', ?)",
                table, code);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, status, locks_at, locked_at, remainder_rule)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Test?', '["Yes","No"]'::jsonb,
                        100, false, 'SETTLED', now(), now(), 'earliest'),
                       (?, ?, ?, 'u2pick', '{}'::jsonb, 'Fun?', '["Yes","No"]'::jsonb,
                        0, true, 'SETTLED', now(), now(), 'earliest')
                """, bet, table, first, funBet, table, first);
        return new Fixture(table, bet, funBet, first, second);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent transaction interrupted", exception);
        }
    }

    private record Fixture(UUID table, UUID bet, UUID funBet, UUID first, UUID second) {}
}
