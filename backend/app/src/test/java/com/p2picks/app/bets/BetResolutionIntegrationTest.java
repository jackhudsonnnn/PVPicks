package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class BetResolutionIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private U2PickResolutionService u2PickResolution;

    @Test
    void u2PickResolutionPostsZeroSumAndHonorsRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID winner1 = UUID.randomUUID();
        UUID winner2 = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resolvehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin1', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin2', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveloser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, winner1, hash, winner2, hash, loser, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution', 'T', 'Y8R5QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, winner1, table, winner2, table, loser);
        UUID bet = insertDebtBet(table, winner1, host, "PENDING", false, true, false, 101, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants', remainder_rule = 'host', remainder_host_id = ? WHERE id = ?",
                host, bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", bet, winner2, bet, loser);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No')",
                bet, winner2, bet, loser);
        Cookie hostCookie = loginAs("resolvehost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt winnerJwt = decoder.decode(loginAs("resolvewin1").getValue());
        Jwt outsiderJwt = decoder.decode(loginAs("resolveoutside").getValue());
        assertThat(u2PickResolution.resolve(winnerJwt, bet, "Yes", "resolve-winner"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(outsiderJwt, bet, "Yes", "resolve-outsider"))
                .isEqualTo(U2PickResolutionService.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Typo", "resolve-key"))
                .isInstanceOf(U2PickResolutionService.InvalidChoice.class);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.REPLAYED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "No", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.CONFLICT);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-again"))
                .isInstanceOf(U2PickResolutionService.Closed.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, host)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, winner1)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, loser)).isEqualTo(-101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'",
                Integer.class, bet.toString())).isEqualTo(1);
        JsonNode view = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(view)).isEmpty();
        assertThat(view.path("status").asText()).isEqualTo("SETTLED");
        assertThat(view.path("calledBy").asText()).isEqualTo(host.toString());
        assertThat(view.path("winningChoice").asText()).isEqualTo("Yes");
        assertThat(view.path("pointsSettled").asBoolean()).isFalse();
    }

    @Test
    void u2PickResolutionHandlesWashesFourFunAndFrozenFallback() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resultfunhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfunuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfrozen', 1, ?, 'PLAYER', 'FROZEN')
                """, host, hash, player, hash, frozen, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Fun result', 'T', 'Y8R6QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, player, table, frozen);
        UUID fun = insertDebtBet(table, host, host, "PENDING", true, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants' WHERE id = ?", fun);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", fun, player, fun, frozen);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", fun, player);
        Cookie hostCookie = loginAs("resultfunhost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt playerJwt = decoder.decode(loginAs("resultfunuser").getValue());
        assertThat(u2PickResolution.resolve(playerJwt, fun, "Yes", "fun-nonhost"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(hostJwt, fun, "Yes", "fun-host"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, fun)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, fun)).isZero();
        UUID washed = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'me' WHERE id = ?", washed);
        assertThat(u2PickResolution.resolve(playerJwt, washed, null, "null-nonproposer"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        Csrf csrf = csrf();
        mvc.perform(post("/bets/" + washed + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "null-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, washed)).isEqualTo("WASHED");
        assertThat(jdbc.queryForObject("SELECT wash_reason FROM bets WHERE id = ?", String.class, washed))
                .isEqualTo("Outcome fell outside the available choices");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, washed)).isZero();
        UUID sports = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", sports);
        assertThat(u2PickResolution.resolve(hostJwt, sports, "Yes", "sports-manual"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
    }

    @Test
    void u2PickResolutionRejectsPreexistingPendingLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resbadhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resbadplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bad ledger', 'T', 'Y8S4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, -100);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resbadhost").getValue());
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-existing"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already has ledger entries");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                """, Integer.class, bet.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
    }

    @Test
    void u2PickResolutionRetriesWhenMembershipChangesWhileAcquiringLocks() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID joining = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resraceuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resracejoin', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, joining, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution race', 'T', 'Y8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resracehost").getValue());
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, joining);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, joining);
                inserted.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var resolution = executor.submit(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> resolution.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(U2PickResolutionService.MembersChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
            assertThat(u2PickResolution.resolve(jwt, bet, "Yes", "res-race"))
                    .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousU2PickResolutionsPostOnlyOneLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resduelhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resdueluser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution duel', 'T', 'Y8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, host, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'anyone' WHERE id = ?", bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", bet, player);
        Jwt hostJwt = decoder.decode(loginAs("resduelhost").getValue());
        Jwt playerJwt = decoder.decode(loginAs("resdueluser").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(hostJwt, bet, "Yes", "res-duel-host").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(playerJwt, bet, "No", "res-duel-player").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESOLVED", "CLOSED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                    """, Integer.class, bet.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void u2PickResolutionFailsClosedWhenCopiesNeedPropagation() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID copyTable = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'rescopyhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Source', 'T', 'Y8R9QA'), (?, 'Copy', 'T', 'Y8S2QA')
                """, table, copyTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, host, copyTable, host);
        UUID source = insertDebtBet(table, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", source);
        UUID copy = insertDebtBet(copyTable, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET source_bet_id = ?, source_table_id = ? WHERE id = ?", source, table, copy);
        Cookie cookie = loginAs("rescopyhost");
        Jwt jwt = decoder.decode(cookie.getValue());
        assertThat(u2PickResolution.resolve(jwt, copy, "Yes", "res-copy"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, source, "Yes", "res-source"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("propagation");
        Csrf csrf = csrf();
        MvcResult blocked = mvc.perform(post("/bets/" + source + "/settle").cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "res-http-source").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("quick_pick_propagation_unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, source)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, source)).isZero();
    }
}
