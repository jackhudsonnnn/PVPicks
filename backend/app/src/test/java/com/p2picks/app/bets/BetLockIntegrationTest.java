package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.p2picks.app.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class BetLockIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private BetLockService betLocks;

    @Test
    void betLockSnapshotsAndRechecksEligibilityWithoutLosingParticipation() {
        UUID host = UUID.randomUUID();
        UUID exact = UUID.randomUUID();
        UUID over = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID limited = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockexact', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockover', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'locklimited', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, exact, hash, over, hash, frozen, hash, limited, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Lock', 'T', 'V8R5QA', 'host')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_bet) VALUES
                (?, ?, 'HOST', true), (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', true),
                (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', false)
                """, table, host, table, exact, table, over, table, frozen, table, limited);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100), (?, 99)", exact, over);
        UUID bet = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?)
                """, bet, over, bet, frozen, bet, limited);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No'), (?, ?, 'No'), (?, ?, 'No')
                """, bet, over, bet, frozen, bet, limited);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForList("SELECT user_id FROM picks WHERE bet_id = ?", UUID.class, bet))
                .containsExactly(exact);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, bet))
                .isEqualTo(host);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.ALREADY_LOCKED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);

        UUID fun = insertDebtBet(table, over, host, "ACTIVE", true, true, false, 100, 0);
        assertThat(betLocks.lock(fun)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, fun)).isEqualTo(1);
        UUID future = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '1 minute' WHERE id = ?", future);
        assertThat(betLocks.lock(future)).isEqualTo(BetLockService.Outcome.NOT_DUE);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, future)).isEqualTo("ACTIVE");
    }

    @Test
    void betLockRollsBackWhenHostRemainderHasNoHost() {
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'locknohost', 1, ?, 'PLAYER', 'ACTIVE')
                """, player, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'No host', 'T', 'V8R6QA', 'host')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0)", player);
        UUID bet = insertDebtBet(table, player, player, "ACTIVE", false, true, false, 100, 0);
        assertThatThrownBy(() -> betLocks.lock(bet)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'",
                Integer.class, bet.toString())).isZero();
    }

    @Test
    void simultaneousBetLocksSerializeSharedDebtExposure() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID firstTable = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockraceuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES
                (?, 'First', 'T', 'V8R7QA', 'earliest'), (?, 'Second', 'T', 'V8R8QA', 'earliest')
                """, firstTable, secondTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, firstTable, host, firstTable, player, secondTable, host, secondTable, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", player);
        UUID first = insertDebtBet(firstTable, player, host, "ACTIVE", false, true, false, 100, 0);
        UUID second = insertDebtBet(secondTable, player, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", second);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(first);
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(second);
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betLockRetriesWhenPickAppearsWhileWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'locknewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'locknewuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Race', 'T', 'V8R9QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, player);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
                jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, player);
                inserted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var lock = executor.submit(() -> betLocks.lock(bet));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT remainder_rule FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> lock.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BetLockService.ParticipantsChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
            assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
