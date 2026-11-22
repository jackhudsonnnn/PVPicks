package com.p2picks.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PollIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:8080";

    @Test
    void membersStartPollsVoteChangeAndRemoveVotesAndEveryoneSeesTheTally() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pollhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pollmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'polloutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Polls', 'T', 'P9X3QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, table, host, table, member);
        Cookie hostSession = loginAs("pollhost");
        Cookie memberSession = loginAs("pollmember");
        Cookie outsideSession = loginAs("polloutside");
        Csrf csrf = csrf();

        for (String invalid : List.of("{}", "{\"question\":\"Pizza?\"}",
                "{\"question\":\" \",\"options\":[\"Yes\",\"No\"]}",
                "{\"question\":\"Pizza?\",\"options\":[\"Yes\"]}",
                "{\"question\":\"Pizza?\",\"options\":[\"Yes\",\"yes \"]}",
                "{\"question\":\"Pizza?\",\"options\":[\"Yes\",\"\"]}",
                "{\"question\":\"Pizza?\",\"options\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\"]}",
                "{\"question\":\"Pizza?\",\"options\":[\"Yes\",2]}",
                "{\"question\":\"Pizza?\",\"options\":[\"Yes\",\"No\"],\"x\":1}")) {
            mvc.perform(create(table, memberSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        String body = "{\"question\":\" Pizza or tacos? \",\"options\":[\"Pizza\",\" Tacos \",\"Both\"]}";
        mvc.perform(create(table, outsideSession, csrf, "outside", body)).andExpect(status().isNotFound());
        JsonNode created = json.readTree(mvc.perform(create(table, memberSession, csrf, "first", body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("ChatMessage").validate(created)).isEmpty();
        assertThat(created.path("text").asText()).isEqualTo("Pizza or tacos?");
        assertThat(created.path("poll").path("options").toString()).isEqualTo("[\"Pizza\",\"Tacos\",\"Both\"]");
        UUID poll = UUID.fromString(created.path("id").asText());
        // A retry returns the same poll; a different body under the key conflicts.
        JsonNode replay = json.readTree(mvc.perform(create(table, memberSession, csrf, "first", body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("id").asText()).isEqualTo(poll.toString());
        mvc.perform(create(table, memberSession, csrf, "first", "{\"question\":\"Other?\",\"options\":[\"A\",\"B\"]}"))
                .andExpect(status().isConflict());

        mvc.perform(vote(poll, outsideSession, csrf, "outside", 0)).andExpect(status().isNotFound());
        mvc.perform(vote(poll, memberSession, csrf, "too-high", 3)).andExpect(status().isBadRequest());
        mvc.perform(vote(poll, memberSession, csrf, "v1", 0)).andExpect(status().isNoContent());
        mvc.perform(vote(poll, hostSession, csrf, "v1", 1)).andExpect(status().isNoContent());
        mvc.perform(vote(poll, memberSession, csrf, "v2", 2)).andExpect(status().isNoContent());
        assertThat(votes(table, hostSession, poll)).containsExactlyInAnyOrder(member + "=2", host + "=1");
        mvc.perform(withKey(delete("/messages/" + poll + "/vote"), hostSession, csrf, "unvote"))
                .andExpect(status().isNoContent());
        assertThat(votes(table, memberSession, poll)).containsExactly(member + "=2");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'chat.poll.voted.v1'
                """, Integer.class, table.toString())).isEqualTo(4);

        // Taking the permission or turning polls off stops new polls, not voting.
        mvc.perform(withKey(put("/tables/" + table + "/members/" + member + "/permissions"), hostSession, csrf, "perm")
                        .content("{\"permission\":\"polls\",\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(create(table, memberSession, csrf, "no-perm", body)).andExpect(status().isForbidden());
        JsonNode view = json.readTree(mvc.perform(get("/tables/" + table).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(view.path("disabledPermissions").path(member.toString()).toString()).isEqualTo("[\"polls\"]");
        JsonNode off = json.readTree(mvc.perform(withKey(patch("/tables/" + table + "/settings"), hostSession, csrf,
                        "off").content("{\"pollsEnabled\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(off.path("pollsEnabled").asBoolean()).isFalse();
        mvc.perform(create(table, hostSession, csrf, "host-off", body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                SELECT text FROM chat_messages WHERE table_id = ? AND kind = 'system' ORDER BY message_order DESC LIMIT 1
                """, String.class, table)).isEqualTo("Polls turned off");
        mvc.perform(vote(poll, memberSession, csrf, "still-open", 0)).andExpect(status().isNoContent());

        // A blocked author's poll is hidden from the blocker.
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", host, member);
        mvc.perform(vote(poll, hostSession, csrf, "blocked", 0)).andExpect(status().isNotFound());
    }

    private List<String> votes(UUID table, Cookie session, UUID poll) throws Exception {
        JsonNode window = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode message : window.path("items")) {
            assertThat(OpenApiSchemas.load("ChatMessage").validate(message)).isEmpty();
            if (!message.path("id").asText().equals(poll.toString())) continue;
            message.path("poll").path("votes").forEach(vote ->
                    result.add(vote.path("userId").asText() + "=" + vote.path("option").asInt()));
        }
        return result;
    }

    private MockHttpServletRequestBuilder create(UUID table, Cookie session, Csrf csrf, String key, String body) {
        return withKey(post("/tables/" + table + "/polls"), session, csrf, key).content(body);
    }

    private MockHttpServletRequestBuilder vote(UUID poll, Cookie session, Csrf csrf, String key, int option) {
        return withKey(put("/messages/" + poll + "/vote"), session, csrf, key).content("{\"option\":" + option + "}");
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, Cookie session,
            Csrf csrf, String key) {
        return request.cookie(session, csrf.cookie()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON);
    }
}
