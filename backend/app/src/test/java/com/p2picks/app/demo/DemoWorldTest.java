package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.IntegrationTestSupport;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds the demo world into a database of its own, so the shared test database is never wiped. */
class DemoWorldTest extends IntegrationTestSupport {
    private JdbcTemplate demo;
    private TransactionTemplate transaction;
    private DemoReset reset;
    private String name;

    @BeforeEach
    void ownDatabase() {
        name = "demo_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + name);
        String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        DataSource source = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        demo = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var none = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        reset = new DemoReset(demo, new DataSourceTransactionManager(source), passwords, Clock.systemUTC(),
                none.getBeanProvider(DemoBots.class), none.getBeanProvider(DemoHistory.class),
                none.getBeanProvider(DemoGames.class));
    }

    @AfterEach
    void dropDatabase() {
        jdbc.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    private int count(String sql, Object... args) {
        return demo.queryForObject(sql, Integer.class, args);
    }

    private int friends(UUID user) {
        return count("""
                SELECT count(*) FROM follows a JOIN follows b
                  ON b.follower_id = a.followee_id AND b.followee_id = a.follower_id
                WHERE a.follower_id = ?
                """, user);
    }

    @Test
    void seedsTheMainAccountsAsDescribed() {
        DemoReset.Summary summary = reset.reset();
        assertThat(summary.users()).isEqualTo(DemoRoster.SIZE + 4);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(DemoRoster.SIZE + 4);

        UUID newbie = DemoWorld.NEWBIE;
        assertThat(count("SELECT count(*) FROM follows WHERE follower_id = ? OR followee_id = ?", newbie, newbie)).isZero();
        assertThat(count("SELECT count(*) FROM table_members WHERE user_id = ?", newbie)).isZero();
        assertThat(count("SELECT count(*) FROM blocks WHERE blocker_id = ? OR blocked_id = ?", newbie, newbie)).isZero();

        UUID intermediate = DemoWorld.INTERMEDIATE;
        assertThat(friends(intermediate)).isEqualTo(DemoWorld.INTERMEDIATE_FRIENDS);
        assertThat(count("SELECT count(*) FROM table_members WHERE user_id = ?", intermediate)).isBetween(2, 4);
        assertThat(count("SELECT count(*) FROM blocks WHERE blocker_id = ? OR blocked_id = ?", intermediate, intermediate))
                .isZero();

        UUID pro = DemoWorld.PRO;
        assertThat(friends(pro)).isEqualTo(DemoWorld.PRO_FRIENDS);
        assertThat(count("SELECT count(*) FROM table_members WHERE user_id = ?", pro)).isGreaterThanOrEqualTo(12);
        assertThat(count("SELECT count(*) FROM blocks WHERE blocker_id = ?", pro)).isEqualTo(4);
        assertThat(count("""
                SELECT count(*) FROM blocks b WHERE b.blocked_id = ? AND EXISTS (
                  SELECT 1 FROM table_members m JOIN table_members p ON p.table_id = m.table_id AND p.user_id = ?
                  WHERE m.user_id = b.blocker_id)
                """, pro, pro)).as("people in his tables block him").isEqualTo(3);
        assertThat(count("SELECT count(*) FROM table_invites WHERE user_id = ? AND status = 'PENDING'", pro)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM table_members WHERE table_id = ?", DemoWorld.CROWD_TABLE))
                .as("Mosh Pit: the pro and the whole crowd").isEqualTo(DemoRoster.CROWD + 1);
        assertThat(count("SELECT count(*) FROM table_members WHERE table_id = ? AND role = 'HOST' AND user_id = ?",
                DemoWorld.CROWD_TABLE, pro)).as("the pro hosts Mosh Pit").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM table_members WHERE table_id = ? AND role = 'HOST'",
                DemoWorld.CROWD_TABLE)).as("and is its only host").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM table_members WHERE table_id = ? AND joined_at > now()",
                DemoWorld.CROWD_TABLE)).as("nobody joins in the future").isZero();
        assertThat(count("""
                SELECT count(*) FROM blocks b JOIN follows f
                  ON (f.follower_id = b.blocker_id AND f.followee_id = b.blocked_id)
                  OR (f.follower_id = b.blocked_id AND f.followee_id = b.blocker_id)
                """)).as("blocks remove follows").isZero();
        assertThat(count("SELECT count(*) FROM tables WHERE char_length(name) > 14")).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE username = 'admin' AND role = 'ADMIN'")).isEqualTo(1);
        assertThat(passwords.matches(DemoWorld.PASSWORD, demo.queryForObject(
                "SELECT password_hash FROM users WHERE username = 'pro'", String.class)))
                .as("the shared demo password works for flows that ask for one").isTrue();
    }

    @Test
    void resetWipesAppDataKeepsSportsAndRebuildsTheSameWorld() {
        reset.reset();
        demo.update("""
                INSERT INTO games (id, league, provider_id, starts_at, status, home_abbreviation, home_name,
                                   home_city, away_abbreviation, away_name, away_city, observed_at)
                VALUES ('NBA-KEEP', 'NBA', 'KEEP', now(), 'SCHEDULED', 'DEN', 'Nuggets', 'Denver', 'NOP',
                        'Pelicans', 'New Orleans', now())
                """);
        demo.update("INSERT INTO auth_sessions (id, user_id, created_at, expires_at) VALUES (?, ?, now(), now() + interval '1 hour')",
                UUID.randomUUID(), DemoWorld.PRO);
        demo.update("DELETE FROM follows WHERE follower_id = ?", DemoWorld.PRO);

        reset.reset();
        assertThat(count("SELECT count(*) FROM auth_sessions")).as("everyone signed out").isZero();
        assertThat(count("SELECT count(*) FROM games WHERE id = 'NBA-KEEP'")).as("sports kept").isEqualTo(1);
        assertThat(friends(DemoWorld.PRO)).isEqualTo(DemoWorld.PRO_FRIENDS);
        assertThat(demo.queryForObject("SELECT id FROM users WHERE username = 'pro'", UUID.class))
                .as("stable ids").isEqualTo(DemoWorld.PRO);
        assertThat(count("""
                SELECT count(*) FROM pg_trigger WHERE tgname = 'ledger_entries_append_only' AND tgenabled = 'O'
                """)).as("append-only guard back on").isEqualTo(1);
    }
}
