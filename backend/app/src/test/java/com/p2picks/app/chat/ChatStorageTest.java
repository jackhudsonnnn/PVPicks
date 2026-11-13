package com.p2picks.app.chat;

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
class ChatStorageTest {

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
    void validatesMessageShapesAndTableReferences() {
        Fixture fixture = fixture("23BCDE");
        UUID otherTable = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Other', 'T', '24BCDE')",
                otherTable);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now())
                """, bet, fixture.table(), fixture.first());
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)",
                settlement, fixture.table(), fixture.first());
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                UUID.randomUUID(), fixture.table(), fixture.first(), "Hello");
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                UUID.randomUUID(), fixture.table(), "Picks locked");
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                UUID.randomUUID(), fixture.table(), fixture.first(), bet);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                UUID.randomUUID(), fixture.table(), settlement);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                        UUID.randomUUID(), fixture.table(), fixture.first(), " "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                        UUID.randomUUID(), otherTable, fixture.first(), bet))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                        UUID.randomUUID(), fixture.table(), settlement))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reactionsAreUniquePerUserAndReadPositionSurvivesMessageDeletion() {
        Fixture fixture = fixture("25BCDE");
        UUID firstMessage = UUID.randomUUID();
        UUID secondMessage = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                firstMessage, fixture.table(), fixture.first(), "Hello");
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                secondMessage, fixture.table(), fixture.second(), "Reply");
        long firstOrder = jdbc.queryForObject("SELECT message_order FROM chat_messages WHERE id = ?",
                Long.class, firstMessage);
        long secondOrder = jdbc.queryForObject("SELECT message_order FROM chat_messages WHERE id = ?",
                Long.class, secondMessage);
        assertThat(secondOrder).isGreaterThan(firstOrder);
        jdbc.update("INSERT INTO chat_read_positions (table_id, user_id, last_read_order) VALUES (?, ?, ?)",
                fixture.table(), fixture.second(), firstOrder);
        jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)",
                firstMessage, fixture.second(), "A");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, ?)",
                        firstMessage, fixture.second(), "B"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE reactions SET emoji = ? WHERE message_id = ? AND user_id = ?",
                "B", firstMessage, fixture.second());
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_messages SET message_order = ? WHERE id = ?",
                        secondOrder + 1, firstMessage))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", firstMessage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reactions WHERE message_id = ?",
                        Integer.class, firstMessage)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT last_read_order FROM chat_read_positions WHERE table_id = ? AND user_id = ?
                """, Long.class, fixture.table(), fixture.second())).isEqualTo(firstOrder);
        assertThat(jdbc.queryForList("""
                SELECT id FROM chat_messages WHERE table_id = ? AND message_order > ? ORDER BY message_order
                """, UUID.class, fixture.table(), firstOrder)).containsExactly(secondMessage);
    }

    @Test
    void concurrentMessagesReceivePositionsInCommitOrder() throws Exception {
        Fixture fixture = fixture("26BCDE");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondInserted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWriter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                insertText(first, fixture.table(), fixture.first());
                firstInserted.countDown();
                await(releaseFirst);
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondWriter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                insertText(second, fixture.table(), fixture.second());
                secondInserted.countDown();
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondInserted.await(500, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            firstWriter.get(10, TimeUnit.SECONDS);
            secondWriter.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("SELECT id FROM chat_messages WHERE table_id = ? ORDER BY message_order",
                            UUID.class, fixture.table()))
                    .containsExactly(first, second);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void insertText(UUID id, UUID table, UUID author) {
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                id, table, author);
    }

    private static Fixture fixture(String code) {
        UUID table = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, ?, 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, first, "u" + first.toString().substring(0, 12).replace("-", ""),
                second, "u" + second.toString().substring(0, 12).replace("-", ""));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Chat', 'T', ?)", table, code);
        return new Fixture(table, first, second);
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

    private record Fixture(UUID table, UUID first, UUID second) {}
}
