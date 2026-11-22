package com.p2picks.app.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.common.MembershipLimits;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Membership limits (domain rules, "Membership limits"): 100 tables per account, 500 members per table, 100 waiting
 * requests per account, and requests that expire after 30 days. Each is enforced on every path that adds someone:
 * creating a table, a host adding members (open or request), and accepting a request.
 */
class MembershipLimitsIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private InvitationExpiry expiry;

    private String prefix() {
        StringBuilder prefix = new StringBuilder("ml");
        for (int i = 0; i < 5; i++) prefix.append((char) ('a' + RANDOM.nextInt(26)));
        return prefix.toString();
    }

    private UUID user(String username) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, id, username, passwords.encode("a secure test password"));
        return id;
    }

    /** {@code count} accounts that never sign in (no usable password needed). */
    private List<UUID> crowd(String prefix, int count) {
        List<UUID> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new Object[] {id, prefix + "c" + i});
        }
        jdbc.batchUpdate("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 1, 'x', 'PLAYER', 'ACTIVE')
                """, rows);
        return ids;
    }

    private UUID table(UUID host, boolean archived) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at) VALUES (?, 'Limit', 'T', ?, ?)
                """, id, code(), archived ? Timestamp.from(Instant.now()) : null);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", id, host);
        return id;
    }

    /** Puts {@code user} at {@code count} more open tables, each hosted by someone else. */
    private void seatAt(UUID user, UUID host, int count) {
        for (int i = 0; i < count; i++) {
            UUID table = table(host, false);
            jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        }
    }

    private UUID request(UUID table, UUID from, UUID to, Instant sent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                """, id, table, from, to, Timestamp.from(sent));
        return id;
    }

    private void follow(UUID follower, UUID followee) {
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", follower, followee);
    }

    private int tablesOf(UUID user) {
        return MembershipLimits.tables(jdbc, user);
    }

    private String inviteStatus(UUID invite) {
        return jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite);
    }

    private MvcResult post(String path, Cookie session, Csrf csrf, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode outcomes(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String code() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    @Test
    void creatingATableStopsAtOneHundredOpenTables() throws Exception {
        String p = prefix();
        UUID owner = user(p + "o");
        UUID other = crowd(p, 1).getFirst();
        seatAt(owner, other, MembershipLimits.TABLES_PER_USER - 1);
        UUID gone = table(other, true);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", gone, owner);
        Cookie session = loginAs(p + "o");
        Csrf csrf = csrf();

        MvcResult hundredth = post("/tables", session, csrf, "{\"name\":\"Last\",\"emoji\":\"🏈\"}");
        assertThat(hundredth.getResponse().getStatus()).as("a deleted table doesn't count").isEqualTo(201);
        assertThat(tablesOf(owner)).isEqualTo(MembershipLimits.TABLES_PER_USER);

        MvcResult refused = post("/tables", session, csrf, "{\"name\":\"Over\",\"emoji\":\"🏈\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        JsonNode body = json.readTree(refused.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("table_limit");
        assertThat(body.path("message").asText()).isEqualTo("You're at 100 tables. Leave one first.");
        assertThat(tablesOf(owner)).isEqualTo(MembershipLimits.TABLES_PER_USER);
    }

    @Test
    void addingSomeoneRespectsTheirTableAndRequestLimits() throws Exception {
        String p = prefix();
        UUID host = user(p + "h");
        UUID other = crowd(p, 1).getFirst();
        UUID table = table(host, false);
        UUID atLimit = crowd(p + "a", 1).getFirst();
        UUID fullRequests = crowd(p + "f", 1).getFirst();
        UUID lapsed = crowd(p + "l", 1).getFirst();
        UUID fresh = crowd(p + "n", 1).getFirst();
        // atLimit is a friend (open: would be added directly); the others follow the host (request).
        follow(atLimit, host);
        follow(host, atLimit);
        follow(fullRequests, host);
        follow(lapsed, host);
        follow(fresh, host);
        seatAt(atLimit, other, MembershipLimits.TABLES_PER_USER);
        for (int i = 0; i < MembershipLimits.WAITING_REQUESTS; i++) {
            request(table(other, false), other, fullRequests, Instant.now());
        }
        // An old request to this table no longer blocks a new one, and old requests don't fill the list.
        UUID old = request(table, host, lapsed, Instant.now().minus(31, ChronoUnit.DAYS));
        for (int i = 0; i < 5; i++) request(table(other, false), other, fresh, Instant.now().minus(40, ChronoUnit.DAYS));

        JsonNode result = outcomes(post("/tables/" + table + "/members", loginAs(p + "h"), csrf(),
                "{\"userIds\":[\"" + atLimit + "\",\"" + fullRequests + "\",\"" + lapsed + "\",\"" + fresh + "\"]}"));
        assertThat(result.get(0).path("outcome").asText()).as("at 100 tables: not added").isEqualTo("not_added");
        assertThat(result.get(1).path("outcome").asText()).as("100 requests waiting: none sent")
                .isEqualTo("not_added");
        assertThat(result.get(2).path("outcome").asText()).isEqualTo("invited");
        assertThat(result.get(3).path("outcome").asText()).isEqualTo("invited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, atLimit)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_invites WHERE table_id = ? AND user_id = ?",
                Integer.class, table, fullRequests)).isZero();
        assertThat(inviteStatus(old)).isEqualTo("EXPIRED");
        assertThat(MembershipLimits.waitingRequests(jdbc, lapsed)).isEqualTo(1);
    }

    @Test
    void aFullTableTakesNoOneElse() throws Exception {
        String p = prefix();
        UUID host = user(p + "h");
        UUID table = table(host, false);
        List<Object[]> seats = new ArrayList<>();
        for (UUID member : crowd(p, MembershipLimits.MEMBERS_PER_TABLE - 1)) {
            seats.add(new Object[] {table, member});
        }
        jdbc.batchUpdate("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", seats);
        assertThat(MembershipLimits.members(jdbc, table)).isEqualTo(MembershipLimits.MEMBERS_PER_TABLE);
        UUID friend = crowd(p + "f", 1).getFirst();
        follow(friend, host);
        follow(host, friend);
        JsonNode result = outcomes(post("/tables/" + table + "/members", loginAs(p + "h"), csrf(),
                "{\"userIds\":[\"" + friend + "\"]}"));
        assertThat(result.get(0).path("outcome").asText()).isEqualTo("not_added");

        // A request sent before the table filled up can't be accepted while it's full, and stays waiting.
        UUID invitee = user(p + "i");
        follow(invitee, host);
        UUID invite = request(table, host, invitee, Instant.now());
        MvcResult accepted = mvc.perform(invitationResponse(invite, loginAs(p + "i"), csrf(), "full-1", true))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(accepted.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("table_full");
        assertThat(inviteStatus(invite)).isEqualTo("PENDING");
        assertThat(MembershipLimits.members(jdbc, table)).isEqualTo(MembershipLimits.MEMBERS_PER_TABLE);
    }

    @Test
    void acceptingARequestAtOneHundredTablesWaitsUntilYouLeaveOne() throws Exception {
        String p = prefix();
        UUID host = user(p + "h");
        UUID other = crowd(p, 1).getFirst();
        UUID invitee = user(p + "i");
        follow(invitee, host);
        UUID table = table(host, false);
        UUID invite = request(table, host, invitee, Instant.now());
        seatAt(invitee, other, MembershipLimits.TABLES_PER_USER);
        Cookie session = loginAs(p + "i");

        MvcResult refused = mvc.perform(invitationResponse(invite, session, csrf(), "limit-1", true)).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(refused.getResponse().getContentAsString()).path("message").asText())
                .isEqualTo("You're at 100 tables. Leave one first.");
        assertThat(inviteStatus(invite)).isEqualTo("PENDING");

        // After leaving a table there is room, and the same request goes through.
        jdbc.update("""
                DELETE FROM table_members WHERE user_id = ? AND table_id = (
                  SELECT table_id FROM table_members WHERE user_id = ? AND role = 'MEMBER' LIMIT 1)
                """, invitee, invitee);
        mvc.perform(invitationResponse(invite, session, csrf(), "limit-2", true))
                .andExpect(status().isNoContent());
        assertThat(tablesOf(invitee)).isEqualTo(MembershipLimits.TABLES_PER_USER);
    }

    @Test
    void requestsExpireAfterThirtyDays() throws Exception {
        String p = prefix();
        UUID host = user(p + "h");
        UUID invitee = user(p + "i");
        follow(invitee, host);
        UUID fresh = request(table(host, false), host, invitee, Instant.now().minus(29, ChronoUnit.DAYS));
        UUID old = request(table(host, false), host, invitee, Instant.now().minus(31, ChronoUnit.DAYS));
        UUID older = request(table(host, false), host, invitee, Instant.now().minus(45, ChronoUnit.DAYS));
        Cookie session = loginAs(p + "i");

        JsonNode waiting = json.readTree(mvc.perform(get("/me/invites").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(waiting.size()).as("only the request still inside its 30 days").isEqualTo(1);
        assertThat(waiting.get(0).path("id").asText()).isEqualTo(fresh.toString());

        mvc.perform(invitationResponse(old, session, csrf(), "expired-1", true)).andExpect(status().isConflict());
        assertThat(inviteStatus(old)).isEqualTo("EXPIRED");
        assertThat(MembershipLimits.tables(jdbc, invitee)).isZero();

        assertThat(inviteStatus(older)).as("still stored as pending until the job runs").isEqualTo("PENDING");
        expiry.expire();
        assertThat(inviteStatus(older)).isEqualTo("EXPIRED");
        assertThat(inviteStatus(fresh)).isEqualTo("PENDING");
    }
}
