package com.p2picks.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.transaction.support.TransactionTemplate;

class RealtimeIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:8080";

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Outbox outbox;
    @Autowired
    private SimpUserRegistry registry;
    @Autowired
    private RealtimeSessions sessions;

    private final List<StompTestClient> clients = new ArrayList<>();
    private UUID host;
    private UUID member;
    private UUID outsider;
    private UUID table;
    private String suffix;

    @BeforeEach
    void seed() {
        suffix = randomFrom("abcdefghijklmnopqrstuvwxyz0123456789", 8);
        host = UUID.randomUUID();
        member = UUID.randomUUID();
        outsider = UUID.randomUUID();
        table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, host, "rh" + suffix, hash, member, "rm" + suffix, hash, outsider, "ro" + suffix, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Live', 'T', ?)", table,
                randomFrom("ABCDEFGHJKMNPQRSTUVWXYZ23456789", 6));
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
    }

    @AfterEach
    void disconnect() {
        clients.forEach(StompTestClient::disconnect);
    }

    @Test
    void membersReceiveBetInvalidationsWithoutPicks() throws Exception {
        UUID bet = insertDebtBet(table, member, host, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '10 minutes' WHERE id = ?", bet);
        Cookie memberSession = loginAs("rm" + suffix);
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe(tableFeed("bets"));
        BlockingQueue<JsonNode> memberFeed = connect(memberSession).subscribe(tableFeed("bets"));
        awaitSubscribers(tableFeed("bets"), 2);

        Csrf csrf = csrf();
        mvc.perform(put("/bets/" + bet + "/pick").cookie(memberSession, csrf.cookie())
                        .header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"Yes\"}"))
                .andExpect(status().isNoContent());
        relay.publishPending();

        for (BlockingQueue<JsonNode> feed : List.of(hostFeed, memberFeed)) {
            JsonNode message = next(feed);
            assertThat(message.path("type").asText()).isEqualTo("bet.changed");
            assertThat(message.path("resourceId").asText()).isEqualTo(bet.toString());
            assertThat(RealtimeSchema.SCHEMA.validate(message)).isEmpty();
            assertThat(message.toString()).doesNotContain("Yes");
        }
    }

    @Test
    void nonMembersAndOtherUsersFeedsAreRejected() throws Exception {
        StompTestClient outsiderClient = connect("ro" + suffix);
        outsiderClient.subscribe(tableFeed("bets"));
        assertThat(outsiderClient.ended.get(10, TimeUnit.SECONDS)).contains("error");

        StompTestClient other = connect("ro" + suffix);
        other.subscribe("/user/" + host + "/queue/settlements");
        assertThat(other.ended.get(10, TimeUnit.SECONDS)).contains("error");

        StompTestClient sender = connect("rm" + suffix);
        sender.send("/queue/tables/" + table + "/bets", "{}");
        assertThat(sender.ended.get(10, TimeUnit.SECONDS)).contains("error");
    }

    @Test
    void connectionsRequireCsrfOnConnectAndTheAllowedOrigin() throws Exception {
        Cookie session = loginAs("rm" + suffix);
        Csrf csrf = csrf();
        List<String> cookies = List.of(cookie(session), cookie(csrf.cookie()));
        assertThatThrownBy(() -> StompTestClient.connect(serverPort, "http://evil.example", cookies, csrf.token()))
                .hasMessageContaining("403");
        assertThatThrownBy(() -> StompTestClient.connect(serverPort, ORIGIN, cookies, null))
                .as("CONNECT without a CSRF token").hasMessageContaining("Connection");
        assertThatThrownBy(() -> StompTestClient.connect(serverPort, ORIGIN, cookies, csrf().token()))
                .as("CONNECT with another cookie's token").hasMessageContaining("Connection");
        assertThatThrownBy(() -> StompTestClient.connect(serverPort, ORIGIN, List.of(cookie(session)), csrf.token()))
                .as("handshake without the CSRF cookie").hasMessageContaining("403");
        StompTestClient valid = StompTestClient.connect(serverPort, ORIGIN, cookies, csrf.token());
        clients.add(valid);
        assertThat(valid.ended).isNotDone();
        assertThatThrownBy(() -> StompTestClient.connect(serverPort, ORIGIN, List.of(cookie(csrf.cookie())), csrf.token()))
                .hasMessageContaining("401");
    }

    @Test
    void removedMembersStopReceivingAndRevokedSessionsAreClosed() throws Exception {
        Cookie memberSession = loginAs("rm" + suffix);
        StompTestClient memberClient = connect(memberSession);
        BlockingQueue<JsonNode> memberFeed = memberClient.subscribe(tableFeed("chat"));
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe(tableFeed("chat"));
        awaitSubscribers(tableFeed("chat"), 2);

        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, member);
        append(OutboxEvent.CHAT_REACTION_CHANGED, table, Map.of("tableId", table));
        relay.publishPending();
        assertThat(next(hostFeed).path("type").asText()).isEqualTo("chat.changed");
        assertThat(memberFeed.poll(1, TimeUnit.SECONDS)).as("removed member").isNull();

        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE id = ?",
                UUID.fromString(decoder.decode(memberSession.getValue()).getId()));
        sessions.sweep();
        assertThat(memberClient.ended.get(10, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void settlementChangesReachOnlyTheTwoParties() throws Exception {
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe("/user/queue/settlements");
        BlockingQueue<JsonNode> memberFeed = connect("rm" + suffix).subscribe("/user/queue/settlements");
        BlockingQueue<JsonNode> outsiderFeed = connect("ro" + suffix).subscribe("/user/queue/settlements");
        awaitSubscribers("/user/queue/settlements", 3);

        append(OutboxEvent.SETTLEMENT_NET_CHANGED, host + ":" + member,
                Map.of("userId", host, "otherUserId", member, "beforeCents", 100, "afterCents", 25));
        relay.publishPending();
        for (BlockingQueue<JsonNode> feed : List.of(hostFeed, memberFeed)) {
            JsonNode message = next(feed);
            assertThat(message.path("type").asText()).isEqualTo("settlements.changed");
            assertThat(RealtimeSchema.SCHEMA.validate(message)).isEmpty();
        }
        assertThat(outsiderFeed.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void permissionChangesReachOnlyHostsAndTheAffectedMember() throws Exception {
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, outsider);
        String view = "/user/queue/tables/" + table;
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe(view);
        BlockingQueue<JsonNode> memberFeed = connect("rm" + suffix).subscribe(view);
        BlockingQueue<JsonNode> otherFeed = connect("ro" + suffix).subscribe(view);
        awaitSubscribers(view, 3);

        append(OutboxEvent.TABLE_MEMBER_PERMISSION_CHANGED, table, Map.of("tableId", table, "userId", member));
        relay.publishPending();
        for (BlockingQueue<JsonNode> feed : List.of(hostFeed, memberFeed)) {
            JsonNode message = next(feed);
            assertThat(message.path("type").asText()).isEqualTo("table.changed");
            assertThat(RealtimeSchema.SCHEMA.validate(message)).isEmpty();
        }
        assertThat(otherFeed.poll(1, TimeUnit.SECONDS)).as("another member").isNull();
    }

    @Test
    void profileChangesReachPeopleWhoShareATableUnlessBlocked() throws Exception {
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe("/user/queue/me");
        BlockingQueue<JsonNode> outsiderFeed = connect("ro" + suffix).subscribe("/user/queue/me");
        awaitSubscribers("/user/queue/me", 2);

        append(OutboxEvent.USER_PROFILE_CHANGED, member, Map.of("userId", member));
        relay.publishPending();
        JsonNode message = next(hostFeed);
        assertThat(message.path("type").asText()).isEqualTo("profile.changed");
        assertThat(message.path("resourceId").asText()).isEqualTo(member.toString());
        assertThat(RealtimeSchema.SCHEMA.validate(message)).isEmpty();
        assertThat(outsiderFeed.poll(1, TimeUnit.SECONDS)).as("no shared table").isNull();

        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", member, host);
        append(OutboxEvent.USER_PROFILE_CHANGED, member, Map.of("userId", member));
        relay.publishPending();
        assertThat(hostFeed.poll(2, TimeUnit.SECONDS)).as("blocked by the member").isNull();
    }

    @Test
    void tickerChangesReachEverySignedInSubscriber() throws Exception {
        BlockingQueue<JsonNode> hostFeed = connect("rh" + suffix).subscribe("/user/queue/ticker");
        BlockingQueue<JsonNode> outsiderFeed = connect("ro" + suffix).subscribe("/user/queue/ticker");
        awaitSubscribers("/user/queue/ticker", 2);
        append(OutboxEvent.SPORTS_GAMES_CHANGED, "NBA", Map.of("league", "NBA", "changed", 1));
        relay.publishPending();
        for (BlockingQueue<JsonNode> feed : List.of(hostFeed, outsiderFeed)) {
            JsonNode message = next(feed);
            assertThat(message.path("type").asText()).isEqualTo("ticker.changed");
            assertThat(RealtimeSchema.SCHEMA.validate(message)).isEmpty();
        }
    }

    private String tableFeed(String feed) {
        return "/user/queue/tables/" + table + "/" + feed;
    }

    private StompTestClient connect(String username) throws Exception {
        return connect(loginAs(username));
    }

    private StompTestClient connect(Cookie session) throws Exception {
        Csrf csrf = csrf();
        StompTestClient client = StompTestClient.connect(serverPort, ORIGIN,
                List.of(cookie(session), cookie(csrf.cookie())), csrf.token());
        clients.add(client);
        return client;
    }

    private void append(OutboxEvent type, Object aggregate, Map<String, ?> payload) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> outbox.append(type, aggregate, payload));
    }

    private void awaitSubscribers(String destination, int count) throws InterruptedException {
        eventually(() -> registry.findSubscriptions(s -> s.getDestination().equals(destination)).size() >= count);
    }

    private static JsonNode next(BlockingQueue<JsonNode> feed) throws InterruptedException {
        JsonNode message = feed.poll(30, TimeUnit.SECONDS);
        assertThat(message).as("realtime message").isNotNull();
        return message;
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Condition not met in time");
            Thread.sleep(25);
        }
    }

    private static String randomFrom(String alphabet, int length) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }

    private static String cookie(Cookie cookie) {
        return cookie.getName() + "=" + cookie.getValue();
    }
}
