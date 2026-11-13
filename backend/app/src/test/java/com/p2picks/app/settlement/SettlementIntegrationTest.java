package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.chat.ChatWindowService;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.social.InvitationResponseService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class SettlementIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private ChatWindowService chatWindows;

    @Autowired
    private DebtCapService debtCaps;

    @Autowired
    private InvitationResponseService invitationResponses;

    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Autowired
    private DebtCapController debtCapController;

    @Autowired
    private SettlementGroupService settlementGroups;

    @Autowired
    private SettlementReceiptService settlementReceipts;

    @Autowired
    private SettlementNetReconciliation settlementReconciliation;

    @Test
    void debtReadIncludesCurrentExposureAndOpenPersonNetsOnly() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID losing = UUID.randomUUID();
        UUID winning = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debthost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debtpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash, third, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Losing', 'T', 'D6R3QA'), (?, 'Winning', 'T', 'D6R4QA'),
                (?, 'Archived', 'T', 'D6R5QA'), (?, 'Left', 'T', 'D6R6QA')
                """, losing, winning, archived, left);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, losing, user, winning, user, archived, user);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        UUID corrected = insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, -300);
        appendDebtDelta(corrected, losing, user, other, 50);
        insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, 100);
        insertDebtBet(losing, user, other, "PENDING", false, true, false, 150, 0);
        insertDebtBet(losing, user, other, "PENDING", false, false, false, 700, 0);
        insertDebtBet(losing, user, other, "PENDING", true, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "ACTIVE", false, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "SETTLED", false, false, true, 100, -900);
        insertDebtBet(winning, user, other, "SETTLED", false, false, false, 100, 9000);
        insertDebtBet(archived, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtBet(left, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtReceipt(archived, other, user, other, 400, "sent");
        insertDebtReceipt(archived, other, other, user, 100, "unpaid");
        insertDebtReceipt(archived, other, user, other, 10, "unpaid");
        insertDebtReceipt(archived, other, user, other, 20, "disputed");
        insertDebtReceipt(archived, other, user, other, 30, "forgive_asked");
        insertDebtReceipt(archived, third, third, user, 9000, "unpaid");
        insertDebtReceipt(archived, other, user, other, 9000, "paid");
        insertDebtReceipt(archived, other, user, other, 9000, "forgiven");
        mvc.perform(get("/me/debt")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("debtviewer");
        MvcResult response = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode debt = json.readTree(response.getResponse().getContentAsString());
        assertThat(debt.size()).isEqualTo(1);
        assertThat(debt.path("debtCents").asLong()).isEqualTo(660);
        Cookie thirdSession = loginAs("debtpeer");
        JsonNode thirdDebt = json.readTree(mvc.perform(get("/me/debt").cookie(thirdSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(thirdDebt.path("debtCents").asLong()).isEqualTo(9000);
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCapController.debt(validated).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void gatedPointsSettlementHttpRequiresAHostAndReturnsCurrentReceipts() throws Exception {
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

    @Test
    void gatedReceiptHttpPreservesPrivateHistoryAndActionSemantics() throws Exception {
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
    void gatedGroupHttpAppliesActionsAndPreservesHistory() throws Exception {
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
    void gatedGroupHttpHandlesZeroAndUnrepresentableNets() throws Exception {
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

    @Test
    void debtReadUsesExactAggregateArithmeticAndFailsOnUnrepresentableDebt() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtexact', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'debtexactpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Exact', 'T', 'D6R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, 1);
        Cookie session = loginAs("debtexact");
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -100);
        Jwt jwt = decoder.decode(session.getValue());
        assertThatThrownBy(() -> debtCapController.debt(jwt)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void debtCapUpdatesRequireZeroDebtAndAreRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capwriter', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capwriterpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Cap', 'T', 'D8R3QA')", table);
        Cookie session = loginAs("capwriter");
        Csrf csrf = csrf();
        mvc.perform(put("/me/debt-cap").cookie(csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "cap-anonymous")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/me/debt-cap").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/debt-cap").cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "cap-first")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isForbidden());
        for (String invalid : List.of("{}", "{\"capCents\":-100}", "{\"capCents\":150}", "{\"capCents\":1.0}",
                "{\"capCents\":2147483648}", "{\"capCents\":\"100\"}", "{\"capCents\":0,\"extra\":true}")) {
            mvc.perform(debtCapPut(session, csrf, "bad-cap", invalid)).andExpect(status().isBadRequest());
        }
        MvcResult saved = mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2147483600);
        UUID receipt = insertDebtReceipt(table, other, user, other, 1, "sent");
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent());
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":0}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-same", "{\"capCents\":2147483600}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        jdbc.update("UPDATE settlement_lines SET status = 'paid' WHERE id = ?", receipt);
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT cap_cents IS NULL FROM debt_caps WHERE user_id = ?", Boolean.class, user))
                .isTrue();
        mvc.perform(debtCapPut(session, csrf, "cap-zero", "{\"capCents\":0}")).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "cap-zero", "{\"friends\":\"open\",\"following\":\"request\"}"))
                .andExpect(status().isConflict());
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        mvc.perform(debtCapPut(session, csrf, "cap-huge", "{\"capCents\":null}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isZero();
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCaps.replace(validated, "cap-first", 2147483600)).isEqualTo(DebtCapService.Outcome.UNAUTHORIZED);
    }

    @Test
    void debtCapUpdateSerializesWithDebtChangingUserLocks() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'caprace', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capracepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'D8R4QA')", table);
        Jwt jwt = decoder.decode(loginAs("caprace").getValue());
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user);
                insertDebtReceipt(table, other, user, other, 100, "unpaid");
                changed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var update = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateSerializesWithInvitationAcceptance() throws Exception {
        UUID user = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capinvite', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capinvitehost', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Invite', 'T', 'D8R5QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", user, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, user);
        insertDebtBet(table, user, host, "PENDING", false, true, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("capinvite").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var accept = executor.submit(() -> invitationResponses.respond(jwt, invite, true, "invite-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            var cap = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-invite-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(accept.get(10, TimeUnit.SECONDS)).isEqualTo(InvitationResponseService.Outcome.UPDATED);
            assertThatThrownBy(() -> cap.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateRollsBackIfSessionExpiresWhileWaiting() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'capexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", user);
        Jwt validated = decoder.decode(loginAs("capexpiry").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT user_id FROM debt_caps WHERE user_id = ? FOR UPDATE", UUID.class, user);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> debtCaps.replace(expiring, "cap-expiry", 200));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO debt_caps (user_id, cap_cents)%'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void settingsReadsReturnOwnValuesAndRecheckCurrentSession() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'settingone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingtwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        mvc.perform(get("/me/debt-cap")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("settingone");
        Cookie otherSession = loginAs("settingtwo");

        Csrf csrf = csrf();
        mvc.perform(get("/me/notification-preferences").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/notifications").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(post("/notifications/read-all").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.DELETE, "/notifications").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());

        MvcResult emptyDebt = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(emptyDebt.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(emptyDebt.getResponse().getContentAsString()).path("debtCents").asLong()).isZero();
        MvcResult defaultCap = mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(defaultCap.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode cap = json.readTree(defaultCap.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.has("capCents")).isTrue();
        assertThat(cap.path("capCents").isNull()).isTrue();
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0), (?, 321)", user, other);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").asInt()).isZero();
        JsonNode otherCap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherCap.path("capCents").asInt()).isEqualTo(321);
        jdbc.update("UPDATE debt_caps SET cap_cents = 2147483647 WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.path("capCents").asLong()).isEqualTo(2147483647L);
        jdbc.update("UPDATE debt_caps SET cap_cents = NULL WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").isNull()).isTrue();

        Jwt validatedBeforeFreeze = decoder.decode(session.getValue());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        assertThat(debtCapController.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        assertThat(debtCapController.debt(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt-cap").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCapController.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
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
