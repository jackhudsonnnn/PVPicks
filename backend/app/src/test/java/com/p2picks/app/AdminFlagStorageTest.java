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
class AdminFlagStorageTest {
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
    void flagsDefaultToUnflaggedWithoutStrikesAndRequireValidCountsAndUser() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'flag_target', 1, 'test', 'PLAYER', 'ACTIVE')
                """, user);
        jdbc.update("INSERT INTO admin_user_flags (user_id) VALUES (?)", user);
        assertThat(jdbc.queryForObject("SELECT flagged FROM admin_user_flags WHERE user_id = ?", Boolean.class, user))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT strike_count FROM admin_user_flags WHERE user_id = ?", Integer.class, user))
                .isZero();
        jdbc.update("UPDATE admin_user_flags SET flagged = true, strike_count = 3 WHERE user_id = ?", user);
        assertThatThrownBy(() -> jdbc.update("UPDATE admin_user_flags SET strike_count = 4 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE admin_user_flags SET strike_count = -1 WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO admin_user_flags (user_id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT strike_count FROM admin_user_flags WHERE user_id = ?", Integer.class, user))
                .isEqualTo(3);
    }
}
