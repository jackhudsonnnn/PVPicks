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
class ProcessedEventStorageTest {
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
    void deduplicatesPerConsumerEvenAfterInboxEntryIsCleared() {
        UUID user = user();
        UUID event = UUID.randomUUID();
        assertThat(transaction.<Integer>execute(status -> createNotificationOnce(user, event))).isEqualTo(1);
        jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", user);
        assertThat(transaction.<Integer>execute(status -> createNotificationOnce(user, event))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ?", Integer.class, user))
                .isZero();
        assertThat(markProcessed("other-consumer", event)).isEqualTo(1);
        assertThat(markProcessed("other-consumer", event)).isZero();
        assertThatThrownBy(() -> markProcessed("  ", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processingFailureRollsBackMarkerAndAllowsRetry() {
        UUID user = user();
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            markProcessed("notifications", event);
            jdbc.update("""
                    INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone, title)
                    VALUES (?, ?, ?, 'invite', 'invite', '')
                    """, UUID.randomUUID(), user, event);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_events WHERE consumer_name = 'notifications' AND event_id = ?
                """, Integer.class, event)).isZero();
        assertThat(transaction.<Integer>execute(status -> createNotificationOnce(user, event))).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateWaitsForTheFirstTransaction() throws Exception {
        UUID event = UUID.randomUUID();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> transaction.execute(status -> {
                int inserted = markProcessed("concurrent", event);
                firstInserted.countDown();
                await(releaseFirst);
                return inserted;
            }));
            assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> transaction.execute(status -> {
                secondStarted.countDown();
                int inserted = markProcessed("concurrent", event);
                secondFinished.countDown();
                return inserted;
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static int createNotificationOnce(UUID user, UUID event) {
        int inserted = markProcessed("notifications", event);
        if (inserted == 1) {
            jdbc.update("""
                    INSERT INTO notifications (id, recipient_id, source_event_id, notification_key, tone, title)
                    VALUES (?, ?, ?, 'invite', 'invite', 'Invitation')
                    """, UUID.randomUUID(), user, event);
        }
        return inserted;
    }

    private static int markProcessed(String consumer, UUID event) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer_name, event_id) VALUES (?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, consumer, event);
    }

    private static UUID user() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'test', 'PLAYER', 'ACTIVE')
                """, user, "u" + user.toString().substring(0, 12).replace("-", ""));
        return user;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Transaction interrupted", exception);
        }
    }
}
