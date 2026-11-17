package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** Settle-ups (domain rules, "Settle-ups"): settles over chosen receipts, rejections, limits and the review gate. */
class SettleIntegrationTest extends IntegrationTestSupport {
    private static final String CODES = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private TablePointsSettlementService pointsSettlement;

    /** A host and a player at one table, signed in, with a random name prefix. */
    private record Pair(UUID host, UUID player, UUID table, Cookie hostCookie, Cookie playerCookie, Csrf csrf) {}

    private Pair pair() throws Exception {
        StringBuilder prefix = new StringBuilder("st");
        for (int i = 0; i < 6; i++) prefix.append((char) ('a' + RANDOM.nextInt(26)));
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, host, prefix + "h", hash, player, prefix + "p", hash);
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(CODES.charAt(RANDOM.nextInt(CODES.length())));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Settle', 'T', ?)", table, code);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        return new Pair(host, player, table, loginAs(prefix + "h"), loginAs(prefix + "p"), csrf());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String lineStatus(UUID line) {
        return jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, line);
    }

    private long debt(Cookie cookie) throws Exception {
        return body(mvc.perform(get("/me/debt").cookie(cookie)).andExpect(status().isOk()).andReturn())
                .path("debtCents").asLong();
    }

    @Test
    void aSendCountsAsPaidRightAwayAndTheReceiverCanRejectItWithinTheWindow() throws Exception {
        Pair p = pair();
        UUID big = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 500, "unpaid");
        UUID small = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 200, "unpaid");
        UUID back = insertDebtReceipt(p.table(), p.host(), p.host(), p.player(), 300, "unpaid");
        assertThat(debt(p.playerCookie())).isEqualTo(400);

        // Netting: the player owes 700 and is owed 300, so sending all three is one 4.00 payment.
        MvcResult sent = settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", List.of(big, small, back), "send-1");
        assertThat(sent.getResponse().getStatus()).isEqualTo(201);
        JsonNode settle = body(sent);
        assertThat(OpenApiSchemas.load("Settle").validate(settle)).isEmpty();
        assertThat(settle.path("kind").asText()).isEqualTo("sent");
        assertThat(settle.path("payerId").asText()).isEqualTo(p.player().toString());
        assertThat(settle.path("amountCents").asLong()).isEqualTo(400);
        assertThat(settle.path("lines").size()).isEqualTo(3);
        assertThat(settle.path("canReject").asBoolean()).as("not by the sender").isFalse();
        assertThat(List.of(lineStatus(big), lineStatus(small), lineStatus(back))).containsOnly("paid");
        UUID settleId = UUID.fromString(settle.path("id").asText());
        assertThat(settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", List.of(big, small, back), "send-1")
                .getResponse().getStatus()).as("a retry returns the same settle").isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settles WHERE sender_id = ?", Integer.class, p.player()))
                .isEqualTo(1);

        // Settled everywhere except the debt cap, until the window closes.
        JsonNode playerGroup = settleGroups(p.playerCookie()).get(0);
        assertThat(OpenApiSchemas.load("SettlementGroup").validate(playerGroup)).isEmpty();
        assertThat(playerGroup.path("netCents").asLong()).isZero();
        assertThat(playerGroup.path("lines").isEmpty()).isTrue();
        assertThat(playerGroup.path("pendingSettles").get(0).path("canReject").asBoolean()).isFalse();
        assertThat(settleGroups(p.hostCookie()).get(0).path("pendingSettles").get(0).path("canReject").asBoolean())
                .isTrue();
        assertThat(debt(p.playerCookie())).as("a claimed payment frees no cap room yet").isEqualTo(400);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'settlement.group.changed.v1'
                  AND payload->>'userId' = ?
                """, Integer.class, p.player().toString())).isEqualTo(1);

        // Only the receiver can reject, once.
        assertThat(rejectHttp(p.playerCookie(), p.csrf(), settleId, "reject-own").getResponse().getStatus())
                .isEqualTo(404);
        MvcResult rejected = rejectHttp(p.hostCookie(), p.csrf(), settleId, "reject-1");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(rejected).path("rejectedAt").isNull()).isFalse();
        assertThat(List.of(lineStatus(big), lineStatus(small), lineStatus(back))).containsOnly("unpaid");
        assertThat(rejectHttp(p.hostCookie(), p.csrf(), settleId, "reject-2").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(settleGroups(p.playerCookie()).get(0).path("netCents").asLong()).isEqualTo(-400);

        // History keeps the rejected settle for audit.
        JsonNode history = body(mvc.perform(get("/people/" + p.host() + "/settles").cookie(p.playerCookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.path("items").size()).isEqualTo(1);
        assertThat(history.path("items").get(0).path("rejectedAt").isNull()).isFalse();
        assertThat(history.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void aSendPastItsWindowIsFinalAndFreesTheCap() throws Exception {
        Pair p = pair();
        UUID line = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 500, "unpaid");
        UUID settle = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settles (id, sender_id, other_id, kind, payer_id, payee_id, amount_cents, created_at,
                    reject_by)
                VALUES (?, ?, ?, 'sent', ?, ?, 500, now() - interval '3 days', now() - interval '1 day')
                """, settle, p.player(), p.host(), p.player(), p.host());
        jdbc.update("INSERT INTO settle_lines (settle_id, line_id) VALUES (?, ?)", settle, line);
        jdbc.update("UPDATE settlement_lines SET status = 'paid', settle_id = ? WHERE id = ?", settle, line);
        assertThat(debt(p.playerCookie())).isZero();
        assertThat(rejectHttp(p.hostCookie(), p.csrf(), settle, "too-late").getResponse().getStatus()).isEqualTo(409);
        assertThat(settleGroups(p.hostCookie())).as("nothing open or in a window").isEmpty();
    }

    @Test
    void markReceivedAndForgiveAreFinalAndOnlyForTheRightSide() throws Exception {
        Pair p = pair();
        UUID owed1 = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 500, "unpaid");
        UUID owed2 = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100, "unpaid");
        UUID owes = insertDebtReceipt(p.table(), p.host(), p.host(), p.player(), 200, "unpaid");

        MvcResult wrongSide = settleHttp(p.hostCookie(), p.csrf(), p.player(), "sent", List.of(owed1), "wrong-send");
        assertThat(wrongSide.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(wrongSide).path("code").asText()).isEqualTo("invalid_settle");
        assertThat(body(settleHttp(p.hostCookie(), p.csrf(), p.player(), "forgiven", List.of(owed1, owes), "mixed"))
                .path("code").asText()).as("only what's owed to you").isEqualTo("invalid_settle");

        MvcResult received = settleHttp(p.hostCookie(), p.csrf(), p.player(), "received", List.of(owed1, owes),
                "received");
        assertThat(received.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(received).path("rejectBy").isNull()).as("final at once").isTrue();
        assertThat(lineStatus(owed1)).isEqualTo("paid");
        assertThat(lineStatus(owes)).isEqualTo("paid");
        MvcResult stale = settleHttp(p.hostCookie(), p.csrf(), p.player(), "forgiven", List.of(owed1, owed2), "stale");
        assertThat(body(stale).path("code").asText()).isEqualTo("stale_selection");
        assertThat(settleHttp(p.hostCookie(), p.csrf(), p.player(), "forgiven", List.of(owed2), "forgive")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(lineStatus(owed2)).isEqualTo("forgiven");
        assertThat(settleGroups(p.playerCookie())).isEmpty();
        assertThat(debt(p.playerCookie())).isZero();
    }

    @Test
    void twoPeopleCanSendAtMostThreeTimesInADay() throws Exception {
        Pair p = pair();
        List<UUID> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) lines.add(insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100, "unpaid"));
        for (int i = 0; i < 3; i++) {
            JsonNode settle = body(settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", List.of(lines.get(i)),
                    "cap-send-" + i));
            if (i < 2) rejectHttp(p.hostCookie(), p.csrf(), UUID.fromString(settle.path("id").asText()), "cap-rej-" + i);
        }
        MvcResult limited = settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", List.of(lines.get(3)), "cap-4");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(limited).path("code").asText()).isEqualTo("settle_limit");
        assertThat(settleHttp(p.hostCookie(), p.csrf(), p.player(), "received", List.of(lines.get(3)), "cap-recv")
                .getResponse().getStatus()).as("the person owed isn't limited").isEqualTo(201);
    }

    @Test
    void aChangedBalanceMustBeReviewedBeforeSettlingAndReviewsArePrivate() throws Exception {
        Pair p = pair();
        UUID outsider = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, outsider, "so" + outsider.toString().substring(0, 8), passwords.encode("a secure test password"));
        Cookie outsiderCookie = loginAs("so" + outsider.toString().substring(0, 8));
        UUID line = insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100, "unpaid");
        Jwt hostJwt = decoder.decode(p.hostCookie().getValue());
        // The first receipts need no review; a later settlement that changes the balance does, for both.
        assertThat(reviewRevision(p.playerCookie(), p.host())).isNull();
        insertDebtBet(p.table(), p.player(), p.host(), "SETTLED", false, false, false, 100, -125);
        pointsSettlement.settle(hostJwt, p.table(), "review-points-1");
        String first = reviewRevision(p.playerCookie(), p.host());
        assertThat(first).isNotNull();
        assertThat(reviewRevision(p.hostCookie(), p.player())).isEqualTo(first);
        assertThat(settleGroups(outsiderCookie)).isEmpty();
        mvc.perform(get("/users/" + p.host() + "/settlement-review").cookie(outsiderCookie))
                .andExpect(status().isNotFound());
        mvc.perform(get("/people/" + p.host() + "/settles").cookie(outsiderCookie)).andExpect(status().isNotFound());
        JsonNode group = settleGroups(p.playerCookie()).get(0);
        assertThat(group.path("pendingReviewRevision").asText()).isEqualTo(first);
        List<UUID> open = new ArrayList<>();
        group.path("lines").forEach(l -> open.add(UUID.fromString(l.path("id").asText())));
        assertThat(open).contains(line).hasSize(2);

        MvcResult blocked = settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", open, "blocked");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(blocked).path("code").asText()).isEqualTo("settlement_review_required");
        assertThat(ackReview(p.playerCookie(), p.csrf(), p.host(), first, "ack").getResponse().getStatus())
                .isEqualTo(204);
        assertThat(reviewRevision(p.playerCookie(), p.host())).as("acknowledged").isNull();
        assertThat(settleHttp(p.playerCookie(), p.csrf(), p.host(), "sent", open, "after-ack").getResponse()
                .getStatus()).isEqualTo(201);
        assertThat(reviewRevision(p.hostCookie(), p.player())).as("the host's review is their own").isEqualTo(first);
    }

    @Test
    void reviewAcknowledgmentWaitsForConcurrentSettlementAndRejectsOldRevision() throws Exception {
        Pair p = pair();
        insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100, "unpaid");
        insertDebtBet(p.table(), p.player(), p.host(), "SETTLED", false, false, false, 100, -100);
        Jwt hostJwt = decoder.decode(p.hostCookie().getValue());
        pointsSettlement.settle(hostJwt, p.table(), "review-race-first");
        String first = reviewRevision(p.playerCookie(), p.host());
        insertDebtBet(p.table(), p.player(), p.host(), "SETTLED", false, false, false, 100, -50);
        var written = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var settlement = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                pointsSettlement.settle(hostJwt, p.table(), "review-race-second");
                written.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var acknowledgment = executor.submit(
                    () -> ackReview(p.playerCookie(), p.csrf(), p.host(), first, "review-race-ack"));
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
            assertThat(body(stale).path("code").asText()).isEqualTo("stale_review");
            assertThat(reviewRevision(p.playerCookie(), p.host())).isNotEqualTo(first);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void groupsListPeopleYouAreSquareWithLastAndHistoryPages() throws Exception {
        Pair owes = pair();
        Pair square = pair();
        insertDebtReceipt(owes.table(), owes.host(), owes.player(), owes.host(), 300, "unpaid");
        // An even pair: open receipts both ways that net to zero.
        insertDebtReceipt(owes.table(), owes.host(), square.host(), owes.host(), 200, "unpaid");
        insertDebtReceipt(owes.table(), owes.host(), owes.host(), square.host(), 200, "unpaid");
        JsonNode groups = settleGroups(owes.hostCookie());
        assertThat(groups.size()).isEqualTo(2);
        assertThat(groups.get(0).path("otherUserId").asText()).isEqualTo(owes.player().toString());
        assertThat(groups.get(1).path("otherUserId").asText()).isEqualTo(square.host().toString());
        assertThat(groups.get(1).path("netCents").asLong()).isZero();

        Pair busy = pair();
        for (int i = 0; i < 22; i++) {
            insertDebtReceipt(busy.table(), busy.host(), busy.player(), busy.host(), 10, "paid");
        }
        JsonNode first = body(mvc.perform(get("/people/" + busy.player() + "/settles").cookie(busy.hostCookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.path("items").size()).isEqualTo(SettleService.PAGE);
        JsonNode second = body(mvc.perform(get("/people/" + busy.player() + "/settles").cookie(busy.hostCookie())
                .param("cursor", first.path("nextCursor").asText())).andExpect(status().isOk()).andReturn());
        assertThat(second.path("items").size()).isEqualTo(2);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        mvc.perform(get("/people/" + busy.player() + "/settles").cookie(busy.hostCookie()).param("cursor", "%%"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/people/" + busy.player() + "/settles")).andExpect(status().isUnauthorized());
    }

    @Test
    void receiptsWithSomeoneListEveryOpenOneAndPageTheClosedOnes() throws Exception {
        Pair p = pair();
        List<UUID> open = new ArrayList<>();
        for (int i = 0; i < 3; i++) open.add(insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100, "unpaid"));
        List<UUID> closed = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            closed.add(insertDebtReceipt(p.table(), p.host(), p.player(), p.host(), 100,
                    i % 2 == 0 ? "paid" : "forgiven"));
        }
        String path = "/users/" + p.host() + "/settlements";
        JsonNode first = body(mvc.perform(get(path).cookie(p.playerCookie())).andExpect(status().isOk()).andReturn());
        assertThat(first.path("open").size()).isEqualTo(3);
        assertThat(first.path("closed").size()).isEqualTo(20);
        first.path("open").forEach(line -> assertThat(line.path("status").asText()).isEqualTo("unpaid"));
        var lineSchema = OpenApiSchemas.load("SettlementLine");
        first.path("closed").forEach(line -> assertThat(lineSchema.validate(line)).isEmpty());
        String cursor = first.path("nextCursor").asText();
        JsonNode second = body(mvc.perform(get(path).param("cursor", cursor).cookie(p.playerCookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.path("open").size()).as("open ones come only on the first page").isZero();
        assertThat(second.path("closed").size()).isEqualTo(5);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        List<String> seen = new ArrayList<>();
        for (JsonNode page : List.of(first, second)) page.path("closed").forEach(l -> seen.add(l.path("id").asText()));
        assertThat(seen).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(closed.stream().map(UUID::toString).toList());
        mvc.perform(get(path).param("cursor", "bad").cookie(p.playerCookie())).andExpect(status().isBadRequest());
    }

    private MvcResult ackReview(Cookie cookie, Csrf csrf, UUID other, String revision, String key) throws Exception {
        return mvc.perform(post("/users/" + other + "/settlement-review/acknowledge").cookie(cookie, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":\"" + revision + "\"}")).andReturn();
    }
}
