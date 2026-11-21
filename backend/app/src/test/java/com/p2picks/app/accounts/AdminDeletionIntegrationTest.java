package com.p2picks.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The admin deletes a player outright; their open bets wash (owner decisions, 2026-11-18). */
class AdminDeletionIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private String name(String prefix) {
        return prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
    }

    private Cookie admin() throws Exception {
        String username = name("adm");
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 4, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), username, passwords.encode("a secure test password"));
        return loginAs(username);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Cookie session, Object body)
            throws Exception {
        Csrf csrf = csrf();
        request.cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    private UUID create(Cookie admin, String username) throws Exception {
        MockHttpServletResponse created = send(post("/admin/players"), admin, Map.of("username", username));
        assertThat(created.getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(created.getContentAsString()).path("id").asText());
    }

    private UUID table(UUID host, UUID... members) {
        UUID table = UUID.randomUUID();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(CODE_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(CODE_ALPHABET.length())));
        }
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Party', '🏈', ?)", table,
                code.toString());
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        for (UUID member : members) {
            jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, member);
        }
        return table;
    }

    private void pick(UUID bet, UUID user, String choice) {
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, user);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, ?)", bet, user, choice);
    }

    private String status(UUID bet) {
        return jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet);
    }

    @Test
    void deletingAPlayerWashesTheirOpenBetsAndFreesTheirName() throws Exception {
        Cookie admin = admin();
        UUID host = create(admin, name("hst"));
        String doomedName = name("gon");
        UUID doomed = create(admin, doomedName);
        UUID bystander = create(admin, name("bys"));
        UUID table = table(host, doomed, bystander);

        // An Active bet they picked in, a Pending one they picked in, and one they're not part of.
        UUID active = insertDebtBet(table, doomed, host, "ACTIVE", false, true, false, 100, 0);
        pick(active, bystander, "No");
        UUID pending = insertDebtBet(table, doomed, host, "PENDING", false, true, false, 100, 0);
        pick(pending, bystander, "No");
        UUID untouched = insertDebtBet(table, bystander, host, "PENDING", false, true, false, 100, 0);
        pick(untouched, host, "No");

        Cookie player = loginAs(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class,
                host));
        assertThat(send(delete("/admin/players/" + doomed), player, null).getStatus()).isEqualTo(403);
        assertThat(send(delete("/admin/players/" + UUID.randomUUID()), admin, null).getStatus()).isEqualTo(404);

        MockHttpServletResponse deleted = send(delete("/admin/players/" + doomed), admin, null);
        assertThat(deleted.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(deleted.getContentAsString());
        assertThat(body.path("washedBets").asInt()).isEqualTo(2);

        assertThat(status(active)).isEqualTo("WASHED");
        assertThat(status(pending)).isEqualTo("WASHED");
        assertThat(jdbc.queryForObject("SELECT wash_reason FROM bets WHERE id = ?", String.class, pending))
                .isEqualTo("A player's account was deleted");
        assertThat(status(untouched)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id IN (?, ?)", Integer.class,
                active, pending)).isZero();
        assertThat(jdbc.queryForObject("SELECT text FROM chat_messages WHERE table_id = ? AND kind = 'system' "
                + "AND text LIKE '%washed%' LIMIT 1", String.class, table)).contains("account was deleted");
        assertThat(jdbc.queryForMap("SELECT status, username FROM users WHERE id = ?", doomed))
                .containsEntry("status", "DELETED").containsEntry("username", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE user_id = ?", Integer.class, doomed))
                .isZero();

        // The name is free again, and deleting twice finds nobody.
        create(admin, doomedName);
        assertThat(send(delete("/admin/players/" + doomed), admin, null).getStatus()).isEqualTo(404);
    }

    @Test
    void pointsADeletedPlayerWonOrLostCanStillBeSettledAndClosed() throws Exception {
        Cookie admin = admin();
        UUID host = create(admin, name("hst"));
        UUID doomed = create(admin, name("gon"));
        UUID winner = create(admin, name("win"));
        UUID table = table(host, doomed, winner);
        // The doomed player lost 1.50 to the winner on a resolved bet whose points aren't settled yet.
        UUID settled = insertDebtBet(table, doomed, host, "SETTLED", false, true, false, 150, 0);
        pick(settled, winner, "No");
        appendDebtDelta(settled, table, doomed, winner, -150);

        assertThat(send(delete("/admin/players/" + doomed), admin, null).getStatus()).isEqualTo(200);

        Cookie hostCookie = loginAs(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?",
                String.class, host));
        Csrf csrf = csrf();
        MockHttpServletResponse points = mvc.perform(post("/tables/" + table + "/settle-points")
                        .cookie(hostCookie, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "deleted-points"))
                .andReturn().getResponse();
        assertThat(points.getStatus()).isEqualTo(201);
        // Points net through the host: the deleted player owes the host, and the host the winner.
        JsonNode lines = json.readTree(points.getContentAsString()).path("lines");
        assertThat(lines).hasSize(2);
        UUID receipt = null;
        for (JsonNode line : lines) {
            if (line.path("payerId").asText().equals(doomed.toString())) {
                receipt = UUID.fromString(line.path("id").asText());
                assertThat(line.path("payeeId").asText()).isEqualTo(host.toString());
            }
        }
        assertThat(receipt).isNotNull();

        // The host closes it on their own: Mark as settled needs nothing from the deleted player.
        assertThat(settleHttp(hostCookie, csrf(), doomed, "received", java.util.List.of(receipt), "deleted-settle")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, receipt))
                .isEqualTo("paid");
    }

    @Test
    void withoutTheLanPartyCreatingAPlayerMakesNoTable() throws Exception {
        UUID player = create(admin(), name("sol"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE user_id = ?", Integer.class, player))
                .isZero();
    }
}
