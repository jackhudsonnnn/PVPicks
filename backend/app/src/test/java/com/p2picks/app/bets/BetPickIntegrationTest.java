package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

class BetPickIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private BetLockService betLocks;

    @Autowired
    private BetPickService betPicks;

    @Test
    void betPickPreservesParticipationAndRetryPrivacy() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Pick', 'T', 'T8R5QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '10 minutes' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("pickplayer").getValue());
        assertThat(betPicks.change(jwt, bet, null, "pick-clear-before")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Other", "pick-invalid"))
                .isInstanceOf(BetPickService.InvalidChoice.class);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.UPDATED);
        Long order = jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                String.class, bet, player)).isEqualTo("No");
        assertThat(betPicks.change(jwt, bet, "No", "pick-invalid")).isEqualTo(BetPickService.Outcome.CONFLICT);
        assertThat(betPicks.change(jwt, bet, null, "pick-clear")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player)).isEqualTo(order);
        List<String> payloads = jdbc.queryForList("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.pick_count.changed.v1'
                """, String.class, bet.toString());
        assertThat(payloads).hasSize(3);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.size()).isEqualTo(2);
            assertThat(event.path("betId").asText()).isEqualTo(bet.toString());
            assertThat(event.path("tableId").asText()).isEqualTo(table.toString());
        }
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "No", "pick-after-lock"))
                .isInstanceOf(BetPickService.Closed.class);
        jdbc.update("UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UNAUTHORIZED);
    }

    @Test
    void betPicksUseSharedCapAndExemptFourFun() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickcaphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickcapuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick cap', 'T', 'T8R6QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 99)", player);
        insertDebtBet(table, player, host, "PENDING", false, true, false, 50, 0);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 50, 0);
        UUID fun = insertDebtBet(table, player, host, "ACTIVE", true, false, false, 50, 0);
        jdbc.update("""
                UPDATE bets SET locks_at = now() + interval '10 minutes',
                    mode = 'either_or', game_id = 'game-1' WHERE id IN (?, ?)
                """, bet, fun);
        Jwt jwt = decoder.decode(loginAs("pickcapuser").getValue());
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Yes", "pick-cap"))
                .isInstanceOf(BetPickService.DebtCapExceeded.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, fun, "Yes", "pick-fun")).isEqualTo(BetPickService.Outcome.UPDATED);
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-cap")).isEqualTo(BetPickService.Outcome.UPDATED);
    }

    @Test
    void betPickRechecksDeadlineAfterWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickwaithost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickwaituser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick wait', 'T', 'T8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("pickwaituser").getValue());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                held.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '4 seconds' WHERE id = ?", bet);
            var pick = executor.submit(() -> betPicks.change(jwt, bet, "Yes", "pick-wait"));
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < timeout) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < locks_at FROM bets WHERE id = ?",
                    Boolean.class, bet)).isTrue();
            boolean due = false;
            while (!due && System.nanoTime() < timeout) {
                due = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT clock_timestamp() >= locks_at FROM bets WHERE id = ?", Boolean.class, bet));
                if (!due) Thread.sleep(25);
            }
            assertThat(due).isTrue();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> pick.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BetPickService.Closed.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '1 minute' WHERE id = ?", bet);
            assertThat(betPicks.change(jwt, bet, "Yes", "pick-wait")).isEqualTo(BetPickService.Outcome.UPDATED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
