package com.p2picks.app.lan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The LAN party table (owner decisions, 2026-11-18): first player hosts it, everyone after is seated and befriended. */
class PartyTableIntegrationTest extends IntegrationTestSupport {
    @DynamicPropertySource
    static void party(DynamicPropertyRegistry registry) {
        registry.add("pvpicks.lan.party.enabled", () -> "true");
    }

    private String name(String prefix) {
        return prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
    }

    private Cookie admin() throws Exception {
        String username = name("adm");
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 4, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), username, passwords.encode("a secure test password"));
        return loginAs(username);
    }

    private UUID create(Cookie admin, String username) throws Exception {
        Csrf csrf = csrf();
        String body = mvc.perform(post("/admin/players").cookie(admin, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private JsonNode party(Cookie admin) throws Exception {
        return json.readTree(mvc.perform(get("/admin/lan/party").cookie(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("table");
    }

    private boolean follows(UUID follower, UUID followee) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM follows WHERE follower_id = ? AND followee_id = ?)", Boolean.class,
                follower, followee));
    }

    @Test
    void theFirstPlayerHostsNewTableAndEveryoneAfterIsSeatedAsFriendsAndADeletedTableIsReplaced() throws Exception {
        jdbc.update("DELETE FROM lan_party");
        Cookie admin = admin();
        assertThat(party(admin).isNull()).isTrue();

        UUID owner = create(admin, name("own"));
        JsonNode table = party(admin);
        assertThat(table.path("name").asText()).isEqualTo("New Table");
        assertThat(table.path("deleted").asBoolean()).isFalse();
        UUID tableId = UUID.fromString(table.path("id").asText());
        assertThat(table.path("hostIds").get(0).asText()).isEqualTo(owner.toString());

        UUID alice = create(admin, name("ali"));
        UUID bob = create(admin, name("bob"));
        table = party(admin);
        assertThat(table.path("memberIds")).extracting(JsonNode::asText)
                .containsExactly(owner.toString(), alice.toString(), bob.toString());
        assertThat(table.path("hostIds")).hasSize(1);
        assertThat(jdbc.queryForList("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                String.class, tableId, bob)).containsExactly("MEMBER");
        // Everyone at the table is friends with everyone else, both ways.
        for (UUID a : List.of(owner, alice, bob)) {
            for (UUID b : List.of(owner, alice, bob)) {
                if (!a.equals(b)) assertThat(follows(a, b)).as(a + " follows " + b).isTrue();
            }
        }
        // The new player sees it as one of their tables right away.
        JsonNode bobsTables = json.readTree(mvc.perform(get("/tables").cookie(loginAs(
                jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class, bob))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(bobsTables).extracting(node -> node.path("id").asText()).contains(tableId.toString());

        // Once it's deleted, the next player hosts a fresh New Table and the one after joins it.
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", tableId);
        UUID carol = create(admin, name("car"));
        table = party(admin);
        assertThat(table.path("deleted").asBoolean()).isFalse();
        assertThat(table.path("id").asText()).isNotEqualTo(tableId.toString());
        assertThat(table.path("hostIds").get(0).asText()).isEqualTo(carol.toString());
        UUID dave = create(admin, name("dav"));
        assertThat(party(admin).path("memberIds")).extracting(JsonNode::asText)
                .containsExactly(carol.toString(), dave.toString());
        assertThat(follows(dave, carol)).isTrue();
        assertThat(follows(dave, owner)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lan_party", Integer.class)).isEqualTo(1);
    }
}
