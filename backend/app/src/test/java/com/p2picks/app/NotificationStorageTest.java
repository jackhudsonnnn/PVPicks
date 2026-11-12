package com.p2picks.app;

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
class NotificationStorageTest {

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
    void storesPreferencesAndUniqueMutedTables() {
        Fixture fixture = fixture("27BCDE");
        jdbc.update("INSERT INTO notification_preferences (user_id) VALUES (?)", fixture.recipient());
        assertThat(jdbc.queryForObject("SELECT ticket_scope FROM notification_preferences WHERE user_id = ?",
                        String.class, fixture.recipient())).isEqualTo("mine");
        assertThat(jdbc.queryForObject("""
                SELECT enabled AND errors AND ticket_status AND chats AND invites
                FROM notification_preferences WHERE user_id = ?
                """, Boolean.class, fixture.recipient())).isTrue();
        jdbc.update("INSERT INTO notification_muted_tables (user_id, table_id) VALUES (?, ?)",
                fixture.recipient(), fixture.table());
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO notification_muted_tables (user_id, table_id) VALUES (?, ?)",
                        fixture.recipient(), fixture.table()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE notification_preferences SET ticket_scope = 'invalid' WHERE user_id = ?",
                        fixture.recipient()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chatNotificationsHaveMatchingSourcesAndAreRemovedWithTheirMessage() {
        Fixture fixture = fixture("28BCDE");
        UUID message = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID notification = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, fixture.table(), fixture.author());
        String insert = """
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone,
                                           title, table_id, actor_id, source_message_id)
                VALUES (?, ?, ?, ?, 'chat', 'Chat', ?, ?, ?)
                """;
        jdbc.update(insert, notification, fixture.recipient(), event, "chat", fixture.table(), fixture.author(), message);
        assertThatThrownBy(() -> jdbc.update(
                        insert, UUID.randomUUID(), fixture.recipient(), event, "chat",
                        fixture.table(), fixture.author(), message))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, UUID.randomUUID(), fixture.recipient(), event, "another",
                fixture.table(), fixture.author(), message);
        assertThatThrownBy(() -> jdbc.update(
                        insert, UUID.randomUUID(), fixture.recipient(), UUID.randomUUID(), "chat",
                        fixture.table(), fixture.recipient(), message))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE notifications SET notification_order = notification_order + 100000 WHERE id = ?",
                        notification))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", notification);
        assertThat(jdbc.queryForObject(
                        "SELECT read_at IS NOT NULL FROM notifications WHERE id = ?", Boolean.class, notification))
                .isTrue();
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", message);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM notifications WHERE source_message_id = ?", Integer.class, message))
                .isZero();
    }

    @Test
    void concurrentNotificationsForOneRecipientHaveStableOrder() throws Exception {
        Fixture fixture = fixture("29BCDE");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondInserted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWriter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                insertNotice(first, fixture.recipient());
                firstInserted.countDown();
                await(releaseFirst);
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondWriter = executor.submit(() -> transaction.executeWithoutResult(status -> {
                secondStarted.countDown();
                insertNotice(second, fixture.recipient());
                secondInserted.countDown();
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondInserted.await(500, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            firstWriter.get(10, TimeUnit.SECONDS);
            secondWriter.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("""
                    SELECT id FROM notifications WHERE recipient_id = ? ORDER BY notification_order DESC
                    """, UUID.class, fixture.recipient())).containsExactly(second, first);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void insertNotice(UUID id, UUID recipient) {
        jdbc.update("""
                INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone, title)
                VALUES (?, ?, ?, 'test', 'invite', 'Invite')
                """, id, recipient, UUID.randomUUID());
    }

    private static Fixture fixture(String code) {
        UUID recipient = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'test-hash', 'PLAYER', 'ACTIVE'),
                       (?, ?, 2, 'test-hash', 'PLAYER', 'ACTIVE')
                """, recipient, "u" + recipient.toString().substring(0, 12).replace("-", ""),
                author, "u" + author.toString().substring(0, 12).replace("-", ""));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Inbox', 'T', ?)", table, code);
        return new Fixture(recipient, author, table);
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

    private record Fixture(UUID recipient, UUID author, UUID table) {}
}
