package com.p2picks.app.settlement;

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

class ReceiptIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Autowired
    private SettlementGroupService settlementGroups;

    @Autowired
    private SettlementReceiptService settlementReceipts;

    @Test
    void settlementReviewIsPrivateRevisionCheckedAndRetainedAfterClosure() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reviewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewpayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Review', 'T', 'R8V3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -125);
        Cookie hostCookie = loginAs("reviewhost");
        Cookie payerCookie = loginAs("reviewpayer");
        Cookie outsiderCookie = loginAs("reviewother");
        Csrf csrf = csrf();
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        pointsSettlement.settle(hostJwt, table, "review-points-1");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("unpaid");
        String first = reviewRevision(payerCookie, host);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(first);
        mvc.perform(get("/people/settlement-reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/people/settlement-counterparties")).andExpect(status().isUnauthorized());
        assertThat(pendingReviews(outsiderCookie)).isEmpty();
        assertThat(json.readTree(mvc.perform(get("/people/settlement-counterparties").cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())).isEmpty();
        JsonNode pending = pendingReviews(payerCookie);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).path("otherUserId").asText()).isEqualTo(host.toString());
        assertThat(pending.get(0).path("pendingReviewRevision").asText()).isEqualTo(first);
        mvc.perform(get("/users/" + host + "/settlement-review").cookie(outsiderCookie))
                .andExpect(status().isNotFound());
        JsonNode group = json.readTree(mvc.perform(get("/people/settle-groups").cookie(payerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(group.path("pendingReviewRevision").asText()).isEqualTo(first);
        assertThat(OpenApiSchemas.load("SettlementGroup").validate(group)).isEmpty();
        String groupRevision = group.path("revision").asText();
        for (String action : List.of("sent", "ask-forgive")) {
            MvcResult blocked = receiptHttpAction(payerCookie, csrf, line, action, 1, "review-line-" + action);
            assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_review_required");
        }
        for (String action : List.of("sent", "ask")) {
            MvcResult blocked = groupHttpAction(payerCookie, csrf, host, action, groupRevision, "review-group-" + action);
            assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                    .isEqualTo("settlement_review_required");
        }
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(payerCookie, host)).isNull();
        assertThat(pendingReviews(payerCookie)).isEmpty();
        assertThat(pendingReviews(hostCookie).size()).isEqualTo(1);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(first);

        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -50);
        pointsSettlement.settle(hostJwt, table, "review-points-2");
        String second = reviewRevision(hostCookie, payer);
        assertThat(second).isNotEqualTo(first);
        assertThat(reviewRevision(payerCookie, host)).isNull();
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        MvcResult stale = ackReview(hostCookie, csrf, payer, first, "review-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_review");
        assertThat(receiptHttpAction(payerCookie, csrf, line, "sent", 1, "review-send-after-ack")
                .getResponse().getStatus()).isEqualTo(200);
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -25);
        pointsSettlement.settle(hostJwt, table, "review-points-3");
        String reset = reviewRevision(payerCookie, host);
        assertThat(reset).isNotEqualTo(first);
        assertThat(ackReview(payerCookie, csrf, host, first, "review-ack-payer").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(payerCookie, host)).isEqualTo(reset);
        assertThat(receiptHttpAction(hostCookie, csrf, line, "confirm", 3, "review-confirm")
                .getResponse().getStatus()).isEqualTo(200);
        String third = reviewRevision(hostCookie, payer);
        assertThat(third).isNotEqualTo(second);
        assertThat(groupHttpAction(hostCookie, csrf, payer, "forgiven", groupRevision(hostCookie), "review-close")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(hostCookie, payer)).isEqualTo(third);
        assertThat(pendingReviews(hostCookie).get(0).path("pendingReviewRevision").asText()).isEqualTo(third);
        JsonNode closedParties = json.readTree(mvc.perform(get("/people/settlement-counterparties").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(closedParties.size()).isEqualTo(1);
        assertThat(closedParties.get(0).asText()).isEqualTo(payer.toString());
        JsonNode history = json.readTree(mvc.perform(get("/people/settle-groups").cookie(hostCookie)
                        .param("includeHistory", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(history.path("pendingReviewRevision").asText()).isEqualTo(third);
        assertThat(ackReview(hostCookie, csrf, payer, third, "review-ack-host").getResponse().getStatus()).isEqualTo(204);
        assertThat(reviewRevision(hostCookie, payer)).isNull();
        assertThat(pendingReviews(hostCookie)).isEmpty();
    }

    @Test
    void reviewAcknowledgmentWaitsForConcurrentSettlementAndRejectsOldRevision() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reviewracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reviewracepayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Review race', 'T', 'R8V4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, payer);
        insertDebtReceipt(table, host, payer, host, 100, "sent");
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -100);
        Cookie hostCookie = loginAs("reviewracehost");
        Cookie payerCookie = loginAs("reviewracepayer");
        Csrf csrf = csrf();
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        pointsSettlement.settle(hostJwt, table, "review-race-first");
        String first = reviewRevision(payerCookie, host);
        insertDebtBet(table, payer, host, "SETTLED", false, false, false, 100, -50);
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var settlement = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                pointsSettlement.settle(hostJwt, table, "review-race-second");
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var acknowledgment = executor.submit(() -> ackReview(payerCookie, csrf, host, first, "review-race-ack"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            settlement.get(10, TimeUnit.SECONDS);
            MvcResult stale = acknowledgment.get(10, TimeUnit.SECONDS);
            assertThat(stale.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_review");
            assertThat(reviewRevision(payerCookie, host)).isNotEqualTo(first);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void receiptHttpPreservesPrivateHistoryAndActionSemantics() throws Exception {
        UUID payer = UUID.randomUUID();
        UUID payee = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'rcapipayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcapipayee', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcapioutsider', 1, ?, 'PLAYER', 'ACTIVE')
                """, payer, hash, payee, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Receipt API', 'T', 'R5A2QA'), (?, 'Receipt API other', 'T', 'R5A3QA')
                """, table, otherTable);
        UUID line = insertDebtReceipt(table, payee, payer, payee, 300, "unpaid");
        UUID other = insertDebtReceipt(otherTable, payee, payer, payee, 50, "sent");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        Cookie payerCookie = loginAs("rcapipayer");
        Cookie payeeCookie = loginAs("rcapipayee");
        Cookie outsiderCookie = loginAs("rcapioutsider");
        Csrf csrf = csrf();
        String historyPath = "/users/" + payee + "/settlements";
        MvcResult history = mvc.perform(get(historyPath).cookie(payerCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(history.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode lines = json.readTree(history.getResponse().getContentAsString());
        assertThat(lines.size()).isEqualTo(2);
        for (JsonNode receipt : lines) assertThat(OpenApiSchemas.load("SettlementLine").validate(receipt)).isEmpty();
        JsonNode filtered = json.readTree(mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", table.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.get(0).path("id").asText()).isEqualTo(line.toString());
        assertThat(json.readTree(mvc.perform(get(historyPath).cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get(historyPath).cookie(payerCookie).param("tableId", table.toString(), otherTable.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(historyPath)).andExpect(status().isUnauthorized());
        String actionPath = "/settlement-lines/" + line + "/";
        mvc.perform(post(actionPath + "sent").cookie(payerCookie).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "rc-api-no-csrf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isForbidden());
        mvc.perform(post(actionPath + "sent").cookie(outsiderCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-out").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isNotFound());
        mvc.perform(post(actionPath + "sent").cookie(payeeCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-wrong-role").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isForbidden());
        for (String invalid : List.of("{}", "{\"revision\":-1}", "{\"revision\":0.0}",
                "{\"revision\":2147483648}", "{\"revision\":0,\"extra\":true}")) {
            mvc.perform(post(actionPath + "sent").cookie(payerCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "rc-api-invalid").contentType(MediaType.APPLICATION_JSON)
                            .content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(actionPath + "unknown").cookie(payerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "rc-api-invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}")).andExpect(status().isBadRequest());
        MvcResult stale = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 1, "rc-api-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_revision");
        MvcResult asked = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 0, "rc-api-ask");
        assertThat(asked.getResponse().getStatus()).isEqualTo(200);
        assertThat(asked.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode askedLine = json.readTree(asked.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("SettlementLine").validate(askedLine)).isEmpty();
        assertThat(askedLine.path("status").asText()).isEqualTo("forgive_asked");
        assertThat(askedLine.path("forgiveRequestedAt").isTextual()).isTrue();
        MvcResult sent = receiptHttpAction(payerCookie, csrf, line, "sent", 1, "rc-api-sent");
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(sent.getResponse().getContentAsString()).path("forgiveRequestedAt"))
                .isEqualTo(askedLine.path("forgiveRequestedAt"));
        MvcResult replay = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 0, "rc-api-ask");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("status").asText()).isEqualTo("sent");
        assertThat(receiptHttpAction(payerCookie, csrf, line, "sent", 2, "rc-api-ask").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "dispute", 2, "rc-api-dispute").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "decline-forgive", 3, "rc-api-decline")
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult cooldown = receiptHttpAction(payerCookie, csrf, line, "ask-forgive", 4, "rc-api-cooldown");
        assertThat(cooldown.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(cooldown.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("invalid_transition");
        assertThat(receiptHttpAction(payeeCookie, csrf, line, "forgive", 4, "rc-api-forgive").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(receiptHttpAction(payeeCookie, csrf, other, "confirm", 0, "rc-api-confirm").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line))
                .isEqualTo("forgiven");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, other))
                .isEqualTo("paid");
    }

    @Test
    void receiptHistoryAndIndividualActionsPreservePrivacyAndOtherClaims() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'receipthost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'receiptpayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'receiptother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Receipts', 'T', 'R4C3QA'), (?, 'Receipts other', 'T', 'R4C4QA')
                """, table, otherTable);
        UUID line = insertDebtReceipt(table, host, payer, host, 300, "unpaid");
        UUID other = insertDebtReceipt(otherTable, host, payer, host, 50, "sent");
        UUID paid = insertDebtReceipt(table, host, host, payer, 100, "paid");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        Jwt payerJwt = decoder.decode(loginAs("receiptpayer").getValue());
        Jwt hostJwt = decoder.decode(loginAs("receipthost").getValue());
        Jwt outsiderJwt = decoder.decode(loginAs("receiptother").getValue());
        var history = settlementReceipts.history(payerJwt, host, null);
        assertThat(history.authenticated()).isTrue();
        assertThat(history.player()).isTrue();
        assertThat(history.lines()).extracting(SettlementReceiptService.Line::id)
                .containsExactly(other, line, paid);
        assertThat(settlementReceipts.history(payerJwt, host, table).lines())
                .extracting(SettlementReceiptService.Line::id).containsExactly(line, paid);
        assertThat(settlementReceipts.history(outsiderJwt, host, null).lines()).isEmpty();
        assertThat(settlementReceipts.transition(outsiderJwt, line, SettlementReceiptService.Action.CONFIRM, 0, "rc-out")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.NOT_FOUND);
        assertThat(settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.SENT, 0, "rc-forbidden")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.FORBIDDEN);
        assertThatThrownBy(() -> settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 1, "rc-stale"))
                .isInstanceOf(SettlementReceiptService.StaleRevision.class);
        var asked = settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 0, "rc-ask");
        assertThat(asked.line().status()).isEqualTo("forgive_asked");
        assertThat(asked.line().forgiveRequestedAt()).isNotNull();
        var sent = settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 1, "rc-sent");
        assertThat(sent.line().status()).isEqualTo("sent");
        assertThat(sent.line().forgiveRequestedAt()).isEqualTo(asked.line().forgiveRequestedAt());
        assertThatThrownBy(() -> settlementReceipts.transition(hostJwt, line,
                SettlementReceiptService.Action.FORGIVE, 2, "rc-sent-forgive"))
                .isInstanceOf(SettlementReceiptService.InvalidTransition.class);
        var replay = settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.ASK_FORGIVE, 0, "rc-ask");
        assertThat(replay.outcome()).isEqualTo(SettlementReceiptService.Outcome.REPLAYED);
        assertThat(replay.line().status()).isEqualTo("sent");
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 2, "rc-ask")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.CONFLICT);
        var disputed = settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.DISPUTE, 2, "rc-dispute");
        assertThat(disputed.line().status()).isEqualTo("forgive_asked");
        var declined = settlementReceipts.transition(hostJwt, line,
                SettlementReceiptService.Action.DECLINE_FORGIVE, 3, "rc-decline");
        assertThat(declined.line().status()).isEqualTo("unpaid");
        assertThat(declined.line().forgiveRequestedAt()).isNull();
        assertThat(declined.line().forgiveDeclinedAt()).isNotNull();
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '23 hours 59 minutes'
                WHERE id = ?
                """, line);
        assertThatThrownBy(() -> settlementReceipts.transition(payerJwt, line,
                SettlementReceiptService.Action.ASK_FORGIVE, 4, "rc-again"))
                .isInstanceOf(SettlementReceiptService.InvalidTransition.class);
        jdbc.update("UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '24 hours' WHERE id = ?", line);
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.ASK_FORGIVE, 4, "rc-again")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.UPDATED);
        var forgiven = settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.FORGIVE, 5, "rc-forgive");
        assertThat(forgiven.line().status()).isEqualTo("forgiven");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, other))
                .isEqualTo("sent");
        assertThat(settlementReceipts.history(payerJwt, host, null).lines()).hasSize(3);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("receiptpayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(50);
        assertThat(settlementReceipts.transition(hostJwt, other, SettlementReceiptService.Action.CONFIRM, 0, "rc-confirm")
                .line().status()).isEqualTo("paid");
        debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("receiptpayer")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'settlement.line.changed.v1'
                """, Integer.class, line.toString())).isEqualTo(6);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", payer);
        assertThat(settlementReceipts.history(payerJwt, host, null).authenticated()).isFalse();
        assertThat(settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 6, "rc-revoked")
                .outcome()).isEqualTo(SettlementReceiptService.Outcome.UNAUTHORIZED);
    }

    @Test
    void receiptActionsSerializeConflictingRevisions() throws Exception {
        UUID host = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'receiptduelhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rcduelpayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, payer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Receipt race', 'T', 'R4D3QA')", table);
        UUID line = insertDebtReceipt(table, host, payer, host, 100, "unpaid");
        Jwt payerJwt = decoder.decode(loginAs("rcduelpayer").getValue());
        Jwt hostJwt = decoder.decode(loginAs("receiptduelhost").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return settlementReceipts.transition(payerJwt, line, SettlementReceiptService.Action.SENT, 0, "rc-race-payer").outcome().name();
                } catch (SettlementReceiptService.StaleRevision exception) {
                    return "STALE";
                }
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return settlementReceipts.transition(hostJwt, line, SettlementReceiptService.Action.FORGIVE, 0, "rc-race-host").outcome().name();
                } catch (SettlementReceiptService.StaleRevision exception) {
                    return "STALE";
                }
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPDATED", "STALE");
            assertThat(jdbc.queryForObject("SELECT revision FROM settlement_lines WHERE id = ?", Integer.class, line))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'settlement.line.changed.v1'
                    """, Integer.class, line.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void groupHttpAppliesActionsAndPreservesHistory() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupapipeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupapiother', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Group API', 'T', 'G6A2QA')", table);
        UUID owing = insertDebtReceipt(table, host, peer, host, 300, "unpaid");
        UUID offset = insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Cookie hostCookie = loginAs("groupapihost");
        Cookie peerCookie = loginAs("groupapipeer");
        Cookie outsiderCookie = loginAs("groupapiother");
        Csrf csrf = csrf();
        MvcResult listed = mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(listed.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode group = json.readTree(listed.getResponse().getContentAsString()).get(0);
        assertThat(OpenApiSchemas.load("SettlementGroup").validate(group)).isEmpty();
        assertThat(group.path("otherUserId").asText()).isEqualTo(host.toString());
        assertThat(group.path("netCents").asLong()).isEqualTo(-200);
        assertThat(group.path("lines").size()).isEqualTo(2);
        String firstRevision = group.path("revision").asText();
        assertThat(json.readTree(mvc.perform(get("/people/settle-groups").cookie(outsiderCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        mvc.perform(get("/people/settle-groups").cookie(peerCookie).param("includeHistory", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/people/settle-groups").cookie(peerCookie).param("includeHistory", "true", "false"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/people/settle-groups")).andExpect(status().isUnauthorized());
        mvc.perform(post("/people/" + host + "/settle").cookie(peerCookie)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "gp-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"sent\",\"expectedRevision\":\"" + firstRevision + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/people/" + host + "/settle").cookie(peerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "gp-invalid").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"sent\",\"expectedRevision\":\"\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(groupHttpAction(hostCookie, csrf, peer, "sent", firstRevision, "gp-forbidden")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(groupHttpAction(outsiderCookie, csrf, host, "sent", firstRevision, "gp-outside")
                .getResponse().getStatus()).isEqualTo(404);
        MvcResult sent = groupHttpAction(peerCookie, csrf, host, "sent", firstRevision, "gp-sent");
        assertThat(sent.getResponse().getStatus()).isEqualTo(204);
        assertThat(sent.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'sent'",
                Integer.class, owing, offset)).isEqualTo(2);
        assertThat(groupHttpAction(peerCookie, csrf, host, "sent", firstRevision, "gp-sent")
                .getResponse().getStatus()).isEqualTo(204);
        MvcResult stale = groupHttpAction(hostCookie, csrf, peer, "unpaid", firstRevision, "gp-stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(stale.getResponse().getContentAsString()).path("code").asText()).isEqualTo("stale_revision");
        String sentRevision = groupRevision(hostCookie);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "unpaid", sentRevision, "gp-unpaid").getResponse().getStatus())
                .isEqualTo(204);
        String unpaidRevision = groupRevision(peerCookie);
        assertThat(groupHttpAction(peerCookie, csrf, host, "ask", unpaidRevision, "gp-ask").getResponse().getStatus())
                .isEqualTo(204);
        String askRevision = groupRevision(hostCookie);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "decline", askRevision, "gp-decline").getResponse().getStatus())
                .isEqualTo(204);
        String declinedRevision = groupRevision(peerCookie);
        MvcResult cooldown = groupHttpAction(peerCookie, csrf, host, "ask", declinedRevision, "gp-cooldown");
        assertThat(cooldown.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(cooldown.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("invalid_transition");
        assertThat(groupHttpAction(hostCookie, csrf, peer, "forgiven", declinedRevision, "gp-forgiven")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        JsonNode history = json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie)
                        .param("includeHistory", "true")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(history.path("netCents").asLong()).isZero();
        assertThat(history.path("lines").size()).isEqualTo(2);
        assertThat(groupHttpAction(hostCookie, csrf, peer, "forgiven", declinedRevision, "gp-forgiven")
                .getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void groupHttpHandlesZeroAndUnrepresentableNets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupedgehost', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'groupedgepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Group edge', 'T', 'G6A3QA')", table);
        insertDebtReceipt(table, host, peer, host, 100, "unpaid");
        insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Cookie hostCookie = loginAs("groupedgehost");
        Cookie peerCookie = loginAs("groupedgepeer");
        Csrf csrf = csrf();
        String revision = groupRevision(peerCookie);
        assertThat(groupHttpAction(peerCookie, csrf, host, "paid", revision, "gp-zero")
                .getResponse().getStatus()).isEqualTo(204);
        insertDebtReceipt(table, host, peer, host, Long.MAX_VALUE, "unpaid");
        insertDebtReceipt(table, host, peer, host, 1, "unpaid");
        JsonNode minimum = json.readTree(mvc.perform(get("/people/settle-groups").cookie(peerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(minimum.path("netCents").longValue()).isEqualTo(Long.MIN_VALUE);
        MvcResult overflow = mvc.perform(get("/people/settle-groups").cookie(hostCookie))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(overflow.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("group_net_out_of_range");
        assertThat(overflow.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        MvcResult counterparties = mvc.perform(get("/people/settlement-counterparties").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(counterparties.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode counterpartyIds = json.readTree(counterparties.getResponse().getContentAsString());
        assertThat(counterpartyIds.size()).isEqualTo(1);
        assertThat(counterpartyIds.get(0).asText()).isEqualTo(peer.toString());
        UUID reviewRevision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_pair_reviews (user_id, other_user_id, revision)
                VALUES (?, ?, ?)
                """, host, peer, reviewRevision);
        JsonNode pending = pendingReviews(hostCookie);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).path("otherUserId").asText()).isEqualTo(peer.toString());
        assertThat(pending.get(0).path("pendingReviewRevision").asText()).isEqualTo(reviewRevision.toString());
        assertThat(pendingReviews(peerCookie)).isEmpty();
        assertThat(json.readTree(mvc.perform(get("/users/" + peer + "/settlements").cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).size()).isEqualTo(4);
    }

    @Test
    void receiptGroupsApplyNetActionsAtomicallyAcrossOffsets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupcreditor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupdebtor', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Group one', 'T', 'G4A3QA'), (?, 'Group two', 'T', 'G4A4QA')
                """, table, secondTable);
        UUID main = insertDebtReceipt(table, host, peer, host, 1500, "unpaid");
        UUID offset = insertDebtReceipt(secondTable, host, host, peer, 500, "unpaid");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", secondTable);
        Jwt hostJwt = decoder.decode(loginAs("groupcreditor").getValue());
        Jwt peerJwt = decoder.decode(loginAs("groupdebtor").getValue());
        var hostGroup = settlementGroups.read(hostJwt, false).groups().getFirst();
        var peerGroup = settlementGroups.read(peerJwt, false).groups().getFirst();
        assertThat(hostGroup.netCents()).isEqualTo(java.math.BigInteger.valueOf(1000));
        assertThat(peerGroup.netCents()).isEqualTo(java.math.BigInteger.valueOf(-1000));
        assertThat(hostGroup.revision()).isEqualTo(peerGroup.revision());
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.SENT,
                hostGroup.revision(), "group-forbidden")).isEqualTo(SettlementGroupService.Outcome.FORBIDDEN);
        settlementReceipts.transition(hostJwt, offset, SettlementReceiptService.Action.SENT, 0, "group-offset-sent");
        assertThatThrownBy(() -> settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT,
                peerGroup.revision(), "group-stale")).isInstanceOf(SettlementGroupService.StaleRevision.class);
        String revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-offset-ask"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT status = 'sent' AND forgive_requested_at IS NOT NULL FROM settlement_lines WHERE id = ?
                """, Boolean.class, offset)).isTrue();
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.DECLINE, revision, "group-offset-decline"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, offset))
                .isEqualTo("sent");
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT, revision, "group-sent"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'sent'
                """, Integer.class, main, offset)).isEqualTo(2);
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.SENT, revision, "group-sent"))
                .isEqualTo(SettlementGroupService.Outcome.REPLAYED);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        String sentRevision = revision;
        assertThatThrownBy(() -> settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                sentRevision, "group-cant-forgive")).isInstanceOf(SettlementGroupService.InvalidTransition.class);
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.UNPAID, revision, "group-dispute"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-ask"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'forgive_asked'
                    AND forgive_requested_at IS NOT NULL
                """, Integer.class, main, offset)).isEqualTo(2);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.DECLINE, revision, "group-decline"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(peerJwt, false).groups().getFirst().revision();
        String declinedRevision = revision;
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '23 hours 59 minutes'
                WHERE id IN (?, ?)
                """, main, offset);
        assertThatThrownBy(() -> settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK,
                declinedRevision, "group-again")).isInstanceOf(SettlementGroupService.InvalidTransition.class);
        jdbc.update("""
                UPDATE settlement_lines SET forgive_declined_at = clock_timestamp() - interval '24 hours'
                WHERE id IN (?, ?)
                """, main, offset);
        assertThat(settlementGroups.transition(peerJwt, host, SettlementGroupService.Action.ASK, revision, "group-again"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        revision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                revision, "group-forgiven")).isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(settlementGroups.read(hostJwt, false).groups()).isEmpty();
        assertThat(settlementGroups.read(hostJwt, true).groups().getFirst().lines()).hasSize(2)
                .allSatisfy(line -> assertThat(line.status()).isEqualTo("forgiven"));
        assertThat(settlementGroups.transition(hostJwt, peer, SettlementGroupService.Action.FORGIVEN,
                revision, "group-forgiven")).isEqualTo(SettlementGroupService.Outcome.REPLAYED);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(loginAs("groupdebtor")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
    }

    @Test
    void receiptGroupsClearZeroNetAndRollBackFailedBatch() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupzerohost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupzeropeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Zero group', 'T', 'G4Z3QA')", table);
        UUID first = insertDebtReceipt(table, host, peer, host, 100, "unpaid");
        UUID second = insertDebtReceipt(table, host, host, peer, 100, "unpaid");
        Jwt jwt = decoder.decode(loginAs("groupzerohost").getValue());
        var group = settlementGroups.read(jwt, false).groups().getFirst();
        assertThat(group.netCents()).isEqualTo(java.math.BigInteger.ZERO);
        assertThat(settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, group.revision(), "zero-group"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'paid'",
                Integer.class, first, second)).isEqualTo(2);
        UUID later = insertDebtReceipt(table, host, peer, host, 150, "unpaid");
        UUID laterOffset = insertDebtReceipt(table, host, host, peer, 50, "unpaid");
        jdbc.update("UPDATE settlement_lines SET revision = 2147483647 WHERE id = ?", laterOffset);
        String revision = settlementGroups.read(jwt, false).groups().getFirst().revision();
        assertThatThrownBy(() -> settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, revision, "group-rollback"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id IN (?, ?) AND status = 'unpaid'",
                Integer.class, later, laterOffset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.group.changed.v1'
                  AND payload->>'userId' = ?
                """, Integer.class, host.toString())).isEqualTo(1);
        jdbc.update("UPDATE settlement_lines SET revision = 0 WHERE id = ?", laterOffset);
        String fresh = settlementGroups.read(jwt, false).groups().getFirst().revision();
        assertThat(settlementGroups.transition(jwt, peer, SettlementGroupService.Action.PAID, fresh, "group-rollback"))
                .isEqualTo(SettlementGroupService.Outcome.UPDATED);
    }

    @Test
    void groupActionRejectsRevisionChangedByConcurrentSettlement() throws Exception {
        UUID host = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'groupnetrace', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'groupnetpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, peer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Net race', 'T', 'G4R3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        UUID receipt = insertDebtReceipt(table, host, peer, host, 50, "unpaid");
        insertDebtBet(table, peer, host, "SETTLED", false, false, false, 100, -100);
        Jwt hostJwt = decoder.decode(loginAs("groupnetrace").getValue());
        Jwt peerJwt = decoder.decode(loginAs("groupnetpeer").getValue());
        settlementReceipts.transition(peerJwt, receipt, SettlementReceiptService.Action.ASK_FORGIVE, 0, "net-ask");
        settlementReceipts.transition(peerJwt, receipt, SettlementReceiptService.Action.SENT, 1, "net-sent");
        String oldRevision = settlementGroups.read(hostJwt, false).groups().getFirst().revision();
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.query("SELECT id FROM users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                        (rs, row) -> rs.getObject("id", UUID.class), host, peer);
                assertThat(pointsSettlement.settle(hostJwt, table, "net-settlement").outcome())
                        .isEqualTo(TablePointsSettlementService.Outcome.CREATED);
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var action = executor.submit(() -> settlementGroups.transition(hostJwt, peer,
                    SettlementGroupService.Action.PAID, oldRevision, "net-group-action"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> action.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SettlementGroupService.StaleRevision.class);
            var state = settlementReceipts.history(peerJwt, host, table).lines();
            assertThat(state).hasSize(2).allSatisfy(line -> assertThat(line.status()).isEqualTo("unpaid"));
            assertThat(state.stream().filter(line -> line.id().equals(receipt)).findFirst().orElseThrow())
                    .satisfies(line -> {
                        assertThat(line.forgiveRequestedAt()).isNull();
                        assertThat(line.revision()).isEqualTo(3);
                    });
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE event_type = 'settlement.net.changed.v1'
                    AND payload->>'userId' = ? AND payload->>'otherUserId' = ?
                    """, Integer.class, host.toString(), peer.toString())).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private JsonNode pendingReviews(Cookie cookie) throws Exception {
        MvcResult result = mvc.perform(get("/people/settlement-reviews").cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode reviews = json.readTree(result.getResponse().getContentAsString());
        for (JsonNode review : reviews) {
            assertThat(OpenApiSchemas.load("PendingSettlementReview").validate(review)).isEmpty();
        }
        return reviews;
    }

    private MvcResult ackReview(Cookie cookie, Csrf csrf, UUID other, String revision, String key) throws Exception {
        return mvc.perform(post("/users/" + other + "/settlement-review/acknowledge").cookie(cookie, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":\"" + revision + "\"}")).andReturn();
    }
}
