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
    void deduplicatesPerConsumerEvenAfterEffectIsCleared() {
        UUID event = UUID.randomUUID();
        assertThat(transaction.<Integer>execute(status -> createEffectOnce(event))).isEqualTo(1);
        jdbc.update("DELETE FROM outbox WHERE id = ?", event);
        assertThat(transaction.<Integer>execute(status -> createEffectOnce(event))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE id = ?", Integer.class, event))
                .isZero();
        assertThat(markProcessed("other-consumer", event)).isEqualTo(1);
        assertThat(markProcessed("other-consumer", event)).isZero();
        assertThatThrownBy(() -> markProcessed("  ", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void processingFailureRollsBackMarkerAndAllowsRetry() {
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            markProcessed("test-consumer", event);
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, '', 'test', 'test.v1', '{}'::jsonb)
                    """, event);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM processed_events WHERE consumer_name = 'test-consumer' AND event_id = ?
                """, Integer.class, event)).isZero();
        assertThat(transaction.<Integer>execute(status -> createEffectOnce(event))).isEqualTo(1);
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

    private static int createEffectOnce(UUID event) {
        int inserted = markProcessed("test-consumer", event);
        if (inserted == 1) {
            jdbc.update("""
                    INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, 'test', 'test', 'test.v1', '{}'::jsonb)
                    """, event);
        }
        return inserted;
    }

    private static int markProcessed(String consumer, UUID event) {
        return jdbc.update("""
                INSERT INTO processed_events (consumer_name, event_id) VALUES (?, ?)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, consumer, event);
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
