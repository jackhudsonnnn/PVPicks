package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FeedbackStorageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void storesFeedbackAndEnforcesItsBasicShape() {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'feedback_user', 1, 'test-hash', 'PLAYER', 'ACTIVE')
                """, userId);
        String insert = "INSERT INTO feedback (id, user_id, kind, text, link) VALUES (?, ?, ?, ?, ?)";
        UUID id = UUID.randomUUID();
        jdbc.update(insert, id, userId, "gif", "a good gif idea", "HTTPS://example.com/gif");
        jdbc.update(insert, UUID.randomUUID(), userId, "bug", "a".repeat(10), null);
        jdbc.update(insert, UUID.randomUUID(), userId, "other", "a".repeat(1000), null);
        jdbc.update(insert, UUID.randomUUID(), userId, "other", "\uD83D\uDE00".repeat(5), null);
        jdbc.update(insert, UUID.randomUUID(), userId, "other", "a good idea", "https://x/\u0085y");

        assertThat(jdbc.queryForObject("SELECT link FROM feedback WHERE id = ?", String.class, id))
                .isEqualTo("HTTPS://example.com/gif");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, "bug", "a".repeat(4), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, "bug", "a".repeat(1001), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, "unknown", "a good gif idea", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, "gif", "a good gif idea", "not a URL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, "gif", "a good gif idea", "https://"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insert, UUID.randomUUID(), userId, "gif", "a good gif idea", "https://x/\u00A0y"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        insert, UUID.randomUUID(), UUID.randomUUID(), "bug", "a good gif idea", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
