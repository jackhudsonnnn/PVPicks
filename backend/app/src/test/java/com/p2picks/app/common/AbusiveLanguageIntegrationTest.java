package com.p2picks.app.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.p2picks.app.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Slurs are refused wherever people write text others see (owner decision, 2026-11-20). */
class AbusiveLanguageIntegrationTest extends IntegrationTestSupport {
    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Cookie session, Object body)
            throws Exception {
        Csrf csrf = csrf();
        request.cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertRefused(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("abusive_language");
    }

    @Test
    void refusesSlursInChatPollsBetsTableNamesAndUsernames() throws Exception {
        String username = "clean" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 2, ?, 'PLAYER', 'ACTIVE')
                """, id, username, passwords.encode("a secure test password"));
        Cookie session = loginAs(username);
        UUID table = UUID.randomUUID();
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Clean', '🏈', ?)", table,
                code.toString());
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, id);

        assertRefused(send(post("/tables/{id}/messages", table), session, Map.of("kind", "text", "text", "f a g")));
        assertThat(send(post("/tables/{id}/messages", table), session,
                Map.of("kind", "text", "text", "that pick was shit")).getStatus()).isEqualTo(201);
        assertRefused(send(post("/tables/{id}/polls", table), session,
                Map.of("question", "Who's worst?", "options", List.of("Him", "the r3tard"))));
        assertRefused(send(post("/tables/{id}/bets", table), session, Map.of("modeId", "u2pick", "config", Map.of(),
                "question", "Is he a faggot?", "choices", List.of("Yes", "No"), "wagerCents", 100,
                "durationSec", 60, "forFun", false, "settledBy", "hosts")));
        assertRefused(send(post("/tables"), session, Map.of("name", "n1gg@s only", "emoji", "🏈")));
        assertRefused(send(patch("/tables/{id}", table), session, Map.of("name", "Spics")));
        assertRefused(send(patch("/me/profile"), session, Map.of("username", "fag_slayer")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND kind = 'text'",
                Integer.class, table)).isEqualTo(1);
    }
}
