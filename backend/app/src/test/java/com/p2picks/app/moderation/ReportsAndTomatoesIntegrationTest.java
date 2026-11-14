package com.p2picks.app.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ReportsAndTomatoesIntegrationTest extends IntegrationTestSupport {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID message = UUID.randomUUID();

    private void seed(String prefix) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'),
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, alice, prefix + "a", hash, bob, prefix + "b", hash, carol, prefix + "c", hash,
                outsider, prefix + "o", hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'T', 'T', ?), (?, 'O', 'T', ?)
                """, table, code(), other, code());
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, alice, table, bob, table, carol, other, outsider);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'rude')",
                message, table, bob);
    }

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static String code() {
        StringBuilder code = new StringBuilder();
        var random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    private MockHttpServletRequestBuilder postJson(String path, Cookie session, Csrf csrf, String key, String body) {
        return post(path).cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void reportsValidateContextKeepTheMessageAndAreRateLimited() throws Exception {
        seed("rep");
        Cookie session = loginAs("repa");
        Csrf csrf = csrf();
        String valid = """
                {"targetId":"%s","reason":"harassment","note":" mean ","tableId":"%s","messageId":"%s"}
                """.formatted(bob, table, message);

        mvc.perform(postJson("/reports", session, csrf, "report-self", """
                {"targetId":"%s","reason":"spam","note":""}""".formatted(alice))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/reports", session, csrf, "report-other-empty", """
                {"targetId":"%s","reason":"other","note":"  "}""".formatted(bob))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/reports", session, csrf, "report-foreign-table", """
                {"targetId":"%s","reason":"spam","note":"","tableId":"%s"}""".formatted(bob, other)))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/reports", session, csrf, "report-wrong-author", """
                {"targetId":"%s","reason":"spam","note":"","tableId":"%s","messageId":"%s"}"""
                .formatted(carol, table, message))).andExpect(status().isBadRequest());
        mvc.perform(postJson("/reports", session, csrf, "report-extra", """
                {"targetId":"%s","reason":"spam","note":"","extra":1}""".formatted(bob)))
                .andExpect(status().isBadRequest());

        mvc.perform(postJson("/reports", session, csrf, "report-one", valid)).andExpect(status().isCreated());
        mvc.perform(postJson("/reports", session, csrf, "report-one", valid)).andExpect(status().isCreated());
        mvc.perform(postJson("/reports", session, csrf, "report-one", valid.replace("mean", "other")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE reporter_id = ?", Integer.class, alice))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT note || '|' || message_snapshot FROM reports WHERE reporter_id = ?",
                String.class, alice)).isEqualTo("mean|rude");

        for (int i = 1; i < ReportService.DAILY_LIMIT; i++) {
            mvc.perform(postJson("/reports", session, csrf, "report-more-" + i, """
                    {"targetId":"%s","reason":"spam","note":""}""".formatted(carol))).andExpect(status().isCreated());
        }
        mvc.perform(postJson("/reports", session, csrf, "report-over", """
                {"targetId":"%s","reason":"spam","note":""}""".formatted(carol)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void tomatoesReachMembersRespectPermissionsBlocksAndCooldown() throws Exception {
        seed("tom");
        Cookie aliceSession = loginAs("toma");
        Cookie carolSession = loginAs("tomc");
        Csrf csrf = csrf();
        String atBob = "{\"targetId\":\"%s\"}".formatted(bob);
        String path = "/tables/" + table + "/tomatoes";

        mvc.perform(postJson(path, aliceSession, csrf, "tomato-self", "{\"targetId\":\"%s\"}".formatted(alice)))
                .andExpect(status().isNotFound());
        mvc.perform(postJson(path, aliceSession, csrf, "tomato-outsider",
                "{\"targetId\":\"%s\"}".formatted(outsider))).andExpect(status().isNotFound());
        mvc.perform(postJson("/tables/" + other + "/tomatoes", aliceSession, csrf, "tomato-not-member", atBob))
                .andExpect(status().isNotFound());

        mvc.perform(postJson(path, aliceSession, csrf, "tomato-one", atBob)).andExpect(status().isCreated());
        mvc.perform(postJson(path, aliceSession, csrf, "tomato-one", atBob)).andExpect(status().isCreated());
        mvc.perform(postJson(path, aliceSession, csrf, "tomato-two", atBob))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "5"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE event_type = 'table.tomato.thrown.v1' AND aggregate_id = ?
                """, Integer.class, table.toString())).isEqualTo(1);

        JsonNode seen = json.readTree(mvc.perform(get(path).cookie(carolSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(seen.size()).isEqualTo(1);
        assertThat(OpenApiSchemas.load("TomatoThrow").validate(seen.get(0))).isEmpty();
        assertThat(seen.get(0).path("throwerId").asText()).isEqualTo(alice.toString());
        assertThat(seen.get(0).path("targetId").asText()).isEqualTo(bob.toString());
        mvc.perform(get(path).cookie(loginAs("tomo"))).andExpect(status().isNotFound());

        // Someone in a block relationship with either party doesn't see the throw, and can't be targeted.
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", carol, alice);
        assertThat(json.readTree(mvc.perform(get(path).cookie(carolSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).size()).isZero();
        mvc.perform(postJson(path, carolSession, csrf, "tomato-blocked",
                "{\"targetId\":\"%s\"}".formatted(alice))).andExpect(status().isNotFound());

        jdbc.update("UPDATE table_members SET can_react = false WHERE table_id = ? AND user_id = ?", table, carol);
        mvc.perform(postJson(path, carolSession, csrf, "tomato-muted", atBob)).andExpect(status().isForbidden());
    }
}
