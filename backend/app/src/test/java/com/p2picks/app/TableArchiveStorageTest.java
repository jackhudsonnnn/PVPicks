package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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
class TableArchiveStorageTest {
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
    void archiveSnapshotSurvivesMembershipRemovalAndRejectsDuplicateOrMissingReferences() {
        UUID user = user();
        UUID table = table("ABCDEF");
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, user);
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
            jdbc.update("""
                    INSERT INTO table_archive_members (table_id, user_id)
                    SELECT table_id, user_id FROM table_members WHERE table_id = ?
                    """, table);
        });
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM table_archive_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, user)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", table, user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", table, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", UUID.randomUUID(), user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedSnapshotRollsBackArchiveMarker() {
        UUID table = table("BCDEFG");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
            jdbc.update("INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)",
                    table, UUID.randomUUID());
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tables WHERE id = ? AND archived_at IS NULL",
                Integer.class, table)).isEqualTo(1);
    }

    private static UUID user() {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'test', 'PLAYER', 'ACTIVE')
                """, user, "u" + user.toString().substring(0, 12).replace("-", ""));
        return user;
    }

    private static UUID table(String code) {
        UUID table = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Table', 'T', ?)", table, code);
        return table;
    }
}
