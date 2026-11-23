package com.p2picks.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Home's table cards: the latest visible message per table, invite members, and the table name limit. */
class ChatPreviewIntegrationTest extends IntegrationTestSupport {
    private final UUID viewer = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID blocked = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    private void seedUsers(String prefix) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 2, ?, 'PLAYER', 'ACTIVE'),
                (?, ?, 3, ?, 'PLAYER', 'ACTIVE'), (?, ?, 4, ?, 'PLAYER', 'ACTIVE')
                """, viewer, prefix + "v", hash, friend, prefix + "f", hash, blocked, prefix + "b", hash,
                host, prefix + "h", hash);
    }

    private UUID table(String name, UUID... members) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, 'T', ?)", id, name, code());
        for (int i = 0; i < members.length; i++) {
            jdbc.update("INSERT INTO table_members (table_id, user_id, role, joined_at) "
                    + "VALUES (?, ?, ?, now() + make_interval(secs => ?))", id, members[i], i == 0 ? "HOST" : "MEMBER", i);
        }
        return id;
    }

    private static String code() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return code.toString();
    }

    private void text(UUID table, UUID author, String text) {
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                UUID.randomUUID(), table, author, text);
    }

    private Map<String, JsonNode> previews(Cookie session) throws Exception {
        JsonNode body = json.readTree(mvc.perform(get("/me/chat/latest").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Map<String, JsonNode> byTable = new HashMap<>();
        for (JsonNode preview : body) {
            assertThat(OpenApiSchemas.load("ChatPreview").validate(preview)).isEmpty();
            byTable.put(preview.path("tableId").asText(), preview.path("message"));
        }
        return byTable;
    }

    @Test
    void previewsShowTheLatestMessageTheViewerCanSee() throws Exception {
        seedUsers("cp");
        UUID chatty = table("Chatty", viewer, friend, blocked);
        UUID quiet = table("Quiet", viewer, friend);
        UUID hosts = table("Hosts", viewer, friend);
        UUID archived = table("Archived", viewer, friend);
        UUID elsewhere = table("Elsewhere", friend, blocked);
        text(chatty, friend, "next drive is a pick six");
        text(chatty, blocked, "you can't see me");
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", viewer, blocked);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', 'A member is now a host', 'host_promoted', ?)
                """, UUID.randomUUID(), hosts, friend);
        text(archived, friend, "old news");
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        text(elsewhere, friend, "not your table");

        Map<String, JsonNode> previews = previews(loginAs("cpv"));
        assertThat(previews).containsOnlyKeys(chatty.toString(), quiet.toString(), hosts.toString());
        assertThat(previews.get(chatty.toString()).path("text").asText()).isEqualTo("next drive is a pick six");
        assertThat(previews.get(chatty.toString()).path("authorId").asText()).isEqualTo(friend.toString());
        assertThat(previews.get(quiet.toString()).isNull()).isTrue();
        assertThat(previews.get(hosts.toString()).path("kind").asText()).isEqualTo("system");
        assertThat(previews.get(hosts.toString()).path("text").asText()).isEqualTo("cpf is now a host");

        // The blocked author still sees their own latest message.
        assertThat(previews(loginAs("cpb")).get(chatty.toString()).path("text").asText()).isEqualTo("you can't see me");
        mvc.perform(get("/me/chat/latest")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitesListMembersTheInviteeCanSee() throws Exception {
        seedUsers("ci");
        UUID table = table("Parlay Lab", host, friend, blocked);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", viewer, host);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocked, viewer);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                UUID.randomUUID(), table, host, viewer);
        JsonNode invite = json.readTree(mvc.perform(get("/me/invites").cookie(loginAs("civ")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(OpenApiSchemas.load("TableInvite").validate(invite)).isEmpty();
        assertThat(invite.path("memberPreview").toString())
                .as("join order, without the member who blocks the invitee")
                .isEqualTo("[\"" + host + "\",\"" + friend + "\"]");
        assertThat(invite.path("memberCount").asInt()).as("the count includes everyone").isEqualTo(3);
    }

    @Test
    void gifsComeFromTheServerAndBetCardsCarryASummary() throws Exception {
        seedUsers("cg");
        UUID table = table("Cards", viewer, friend);
        Cookie session = loginAs("cgv");
        JsonNode catalog = json.readTree(mvc.perform(get("/gifs").cookie(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("GifCatalog").validate(catalog)).isEmpty();
        assertThat(catalog.path("gifs").size()).isEqualTo(97);
        // Money merged into Hype (owner decision, 2026-11-23), leaving room for the picker's Starred tab.
        assertThat(catalog.path("categories")).extracting(c -> c.path("id").asText())
                .containsExactly("hype", "pain", "react", "roast");
        assertThat(catalog.path("gifs").get(0).path("id").asText()).isEqualTo("g1");

        UUID bet = insertDebtBet(table, viewer, friend, "PENDING", false, false, false, 100, 0);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                UUID.randomUUID(), table, viewer, bet);
        JsonNode card = chatWindow(table, session, null, null).path("items").get(0);
        assertThat(OpenApiSchemas.load("ChatMessage").validate(card)).isEmpty();
        assertThat(card.path("bet").path("status").asText()).isEqualTo("PENDING");
        assertThat(card.path("bet").path("modeId").asText()).isEqualTo("u2pick");
        assertThat(card.path("bet").path("question").asText()).isNotBlank();
    }

    @Test
    void theDatabaseKeepsTableNamesToFourteenCharacters() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, ?, 'T', ?)",
                id, "🙂".repeat(14), code());
        assertThatThrownBy(() -> jdbc.update("UPDATE tables SET name = ? WHERE id = ?", "🙂".repeat(15), id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
