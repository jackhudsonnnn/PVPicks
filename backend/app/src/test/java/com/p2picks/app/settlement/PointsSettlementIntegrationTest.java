package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.chat.ChatWindowService;
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

class PointsSettlementIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private ChatWindowService chatWindows;

    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Autowired
    private SettlementNetReconciliation settlementReconciliation;

    @Test
    void pointsSettlementHttpRequiresAHostAndReturnsCurrentReceipts() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID zeroTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsapiplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsapiother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Points API', 'T', 'P3H4QA'), (?, 'Points API zero', 'T', 'P3H5QA')
                """, table, zeroTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, host, table, player, zeroTable, host);
        UUID bet = insertDebtBet(table, player, host, "SETTLED", false, false, false, 100, -125);
        insertDebtBet(zeroTable, player, host, "SETTLED", false, false, false, 100, -100);
        insertDebtBet(zeroTable, player, host, "SETTLED", false, false, false, 100, 100);
        Cookie hostCookie = loginAs("pointsapihost");
        Cookie playerCookie = loginAs("pointsapiplayer");
        Cookie outsideCookie = loginAs("pointsapiother");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/settle-points";
        mvc.perform(post(path).cookie(hostCookie).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "points-http-no-csrf")).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-anon"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token())).andExpect(status().isBadRequest());
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-body")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).cookie(playerCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-member"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(outsideCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "points-http-outsider"))
                .andExpect(status().isNotFound());
        MvcResult created = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode settlement = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Settlement").validate(settlement)).isEmpty();
        assertThat(settlement.path("tableId").asText()).isEqualTo(table.toString());
        assertThat(settlement.path("hostId").asText()).isEqualTo(host.toString());
        assertThat(settlement.path("lines").size()).isEqualTo(1);
        JsonNode line = settlement.path("lines").get(0);
        assertThat(line.path("payerId").asText()).isEqualTo(player.toString());
        assertThat(line.path("payeeId").asText()).isEqualTo(host.toString());
        assertThat(line.path("amountCents").asLong()).isEqualTo(125);
        UUID settlementId = UUID.fromString(settlement.path("id").asText());
        assertThat(pointsSettlement.readForHost(decoder.decode(playerCookie.getValue()), settlementId).settlement())
                .isNull();
        assertThat(pointsSettlement.readForHost(decoder.decode(outsideCookie.getValue()), settlementId).settlement())
                .isNull();
        assertThat(jdbc.queryForObject("SELECT settlement_id FROM settlement_bets WHERE bet_id = ?",
                UUID.class, bet)).isEqualTo(settlementId);
        jdbc.update("UPDATE settlement_lines SET status = 'sent', revision = revision + 1 WHERE id = ?",
                UUID.fromString(line.path("id").asText()));
        MvcResult replay = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isCreated()).andReturn();
        JsonNode replayed = json.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.path("id").asText()).isEqualTo(settlementId.toString());
        assertThat(replayed.path("lines").get(0).path("status").asText()).isEqualTo("sent");
        assertThat(replayed.path("lines").get(0).path("revision").asInt()).isEqualTo(1);
        MvcResult empty = mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http-empty")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(empty.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("no_eligible_bets");
        String zeroPath = "/tables/" + zeroTable + "/settle-points";
        mvc.perform(post(zeroPath).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isConflict());
        MvcResult zero = mvc.perform(post(zeroPath).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http-zero")).andExpect(status().isCreated()).andReturn();
        JsonNode zeroSettlement = json.readTree(zero.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Settlement").validate(zeroSettlement)).isEmpty();
        assertThat(zeroSettlement.path("lines").isEmpty()).isTrue();
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(post(path).cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "points-http")).andExpect(status().isForbidden());
    }

    @Test
    void pointsSettlementRoutesLedgerAndReconcilesOpenPairClaims() throws Exception {
        UUID host = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID oldTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointshost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsloser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointswinner', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, loser, hash, winner, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Points', 'T', 'P2S3QA'), (?, 'Previous', 'T', 'P2S4QA')
                """, table, oldTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, loser, table, winner);
        UUID first = insertDebtBet(table, loser, winner, "SETTLED", false, false, false, 100, -125);
        UUID fun = insertDebtBet(table, loser, winner, "SETTLED", true, false, false, 100, 0);
        UUID pending = insertDebtBet(table, loser, winner, "PENDING", false, false, false, 100, 0);
        UUID sent = insertDebtReceipt(oldTable, host, loser, host, 40, "sent");
        UUID asked = insertDebtReceipt(oldTable, host, loser, host, 20, "forgive_asked");
        UUID paid = insertDebtReceipt(oldTable, host, loser, host, 1000, "paid");
        jdbc.update("UPDATE settlement_lines SET forgive_requested_at = now() WHERE id = ?", asked);
        Jwt hostJwt = decoder.decode(loginAs("pointshost").getValue());
        Jwt loserJwt = decoder.decode(loginAs("pointsloser").getValue());
        assertThat(pointsSettlement.settle(loserJwt, table, "not-host").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.FORBIDDEN);
        var result = pointsSettlement.settle(hostJwt, table, "points-key");
        assertThat(result.outcome()).isEqualTo(TablePointsSettlementService.Outcome.CREATED);
        assertThat(pointsSettlement.settle(hostJwt, table, "points-key"))
                .isEqualTo(new TablePointsSettlementService.Result(
                        TablePointsSettlementService.Outcome.REPLAYED, result.settlementId()));
        assertThat(pointsSettlement.settle(hostJwt, oldTable, "points-key").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> pointsSettlement.settle(hostJwt, table, "points-new"))
                .isInstanceOf(TablePointsSettlementService.NoEligibleBets.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE settlement_id = ?",
                Integer.class, result.settlementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT bet_id FROM settlement_bets WHERE settlement_id = ?",
                UUID.class, result.settlementId())).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, fun, pending)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE settlement_id = ?",
                Integer.class, result.settlementId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, result.settlementId(), loser, host)).isEqualTo(125);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, result.settlementId(), host, winner)).isEqualTo(125);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'unpaid' AND revision = 1",
                Integer.class, sent, asked)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT forgive_requested_at IS NULL FROM settlement_lines WHERE id = ?",
                Boolean.class, asked)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, paid))
                .isEqualTo("paid");
        JsonNode change = json.readTree(jdbc.queryForObject("""
                SELECT payload::text FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                  AND payload->>'userId' = ? AND payload->>'otherUserId' = ?
                """, String.class, host.toString(), loser.toString()));
        assertThat(change.path("beforeCents").asLong()).isEqualTo(60);
        assertThat(change.path("afterCents").asLong()).isEqualTo(185);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE settlement_id = ? AND kind = 'settlement'",
                Integer.class, result.settlementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.created.v1'
                  AND payload->>'settlementId' = ?
                """, Integer.class, result.settlementId().toString())).isEqualTo(1);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        assertThat(pointsSettlement.settle(hostJwt, table, "points-key").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.FORBIDDEN);
    }

    @Test
    void pointsSettlementAllowsZeroNetAndRollsBackUnrepresentableTotals() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointszero', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'pointszeropeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Zero', 'T', 'P2Z3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID one = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        UUID two = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, 100);
        Jwt jwt = decoder.decode(loginAs("pointszero").getValue());
        var zero = pointsSettlement.settle(jwt, table, "zero-points");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE settlement_id = ?",
                Integer.class, zero.settlementId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE settlement_id = ?",
                Integer.class, zero.settlementId())).isZero();
        var chat = chatWindows.read(jwt, table, new ChatWindowService.Query(ChatWindowService.Direction.LATEST, null, null));
        assertThat(chat.window().items()).filteredOn(message -> zero.settlementId().equals(message.settlementId()))
                .singleElement().satisfies(message -> {
                    assertThat(message.settlementSummary().hostId()).isEqualTo(host);
                    assertThat(message.settlementSummary().lines()).isEmpty();
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, one, two)).isEqualTo(2);
        UUID huge = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, Long.MAX_VALUE);
        UUID extra = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, 1);
        assertThatThrownBy(() -> pointsSettlement.settle(jwt, table, "overflow-points"))
                .isInstanceOf(ArithmeticException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id IN (?, ?) AND points_settled_at IS NOT NULL",
                Integer.class, huge, extra)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ? AND operation = 'table.points-settlement.v1'",
                Integer.class, host)).isEqualTo(1);
        appendDebtDelta(huge, table, peer, host, 1);
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        var exact = pointsSettlement.settle(jwt, table, "overflow-points");
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, exact.settlementId(), host, peer)).isEqualTo(2);
    }

    @Test
    void pointsSettlementRollsBackWhenReceiptReconciliationFails() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsrollback', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsrollpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Rollback', 'T', 'P2R3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID bet = insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        UUID receipt = insertDebtReceipt(table, host, peer, host, 50, "sent");
        jdbc.update("UPDATE settlement_lines SET revision = 2147483647 WHERE id = ?", receipt);
        Jwt jwt = decoder.decode(loginAs("pointsrollback").getValue());
        assertThatThrownBy(() -> pointsSettlement.settle(jwt, table, "rollback-points"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT points_settled_at IS NULL FROM bets WHERE id = ?", Boolean.class, bet)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, receipt))
                .isEqualTo("sent");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_bets WHERE bet_id = ?", Integer.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
        jdbc.update("UPDATE settlement_lines SET revision = 0 WHERE id = ?", receipt);
        assertThat(pointsSettlement.settle(jwt, table, "rollback-points").outcome())
                .isEqualTo(TablePointsSettlementService.Outcome.CREATED);
    }

    @Test
    void pointsSettlementRetriesIfResolutionAddsAffectedUsersDuringLocking() throws Exception {
        UUID host = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID winner = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsracelose', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsracewin', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, loser, hash, winner, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'P2R4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID bet = insertDebtBet(table, loser, winner, "PENDING", false, false, false, 100, 0);
        Cookie cookie = loginAs("pointsracehost");
        Csrf csrf = csrf();
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                        (rs, row) -> rs.getObject("id", UUID.class), loser, winner);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
                jdbc.update("""
                        INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents)
                        VALUES (?, ?, ?, ?, -100), (?, ?, ?, ?, 100)
                        """, UUID.randomUUID(), table, bet, loser, UUID.randomUUID(), table, bet, winner);
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var settlement = executor.submit(() -> mvc.perform(post("/tables/" + table + "/settle-points")
                            .cookie(cookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                            .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "race-points"))
                    .andReturn());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            MvcResult raced = settlement.get(10, TimeUnit.SECONDS);
            assertThat(raced.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(raced.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_affected_users_changed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                    .isZero();
            mvc.perform(post("/tables/" + table + "/settle-points").cookie(cookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "race-points")).andExpect(status().isCreated());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void settlementReconciliationPreservesUnchangedAndFullyClosedGroups() {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsstable', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointstablepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Stable', 'T', 'P2S5QA')", table);
        UUID sent = insertDebtReceipt(table, host, peer, host, 100, "sent");
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                    (rs, row) -> rs.getObject("id", UUID.class), host, peer);
            var before = settlementReconciliation.capture(host, peer);
            insertDebtReceipt(table, host, host, peer, 30, "unpaid");
            insertDebtReceipt(table, host, peer, host, 30, "unpaid");
            settlementReconciliation.reconcile(before);
            assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, sent))
                    .isEqualTo("sent");
            var beforeClose = settlementReconciliation.capture(host, peer);
            jdbc.update("UPDATE settlement_lines SET status = 'paid' WHERE table_id = ?", table);
            settlementReconciliation.reconcile(beforeClose);
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                  AND payload->>'userId' = ?
                """, Integer.class, host.toString())).isZero();
    }

    @Test
    void pointsSettlementSerializesConcurrentMatchingRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pointsduel', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pointsduelpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Concurrent', 'T', 'P2D3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        Jwt jwt = decoder.decode(loginAs("pointsduel").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return pointsSettlement.settle(jwt, table, "duel-points");
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return pointsSettlement.settle(jwt, table, "duel-points");
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(a.outcome(), b.outcome())).containsExactlyInAnyOrder(
                    TablePointsSettlementService.Outcome.CREATED, TablePointsSettlementService.Outcome.REPLAYED);
            assertThat(a.settlementId()).isEqualTo(b.settlementId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlements WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
