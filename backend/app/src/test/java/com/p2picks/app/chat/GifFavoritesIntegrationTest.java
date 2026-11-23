package com.p2picks.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Starring GIFs: a set per player, only library GIFs, capped, newest first. */
class GifFavoritesIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private GifCatalog gifs;

    private Cookie player(UUID id) throws Exception {
        String username = "gif" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 3, ?, 'PLAYER', 'ACTIVE')
                """, id, username, passwords.encode("a secure test password"));
        return loginAs(username);
    }

    private int send(MockHttpServletRequestBuilder request, Cookie session) throws Exception {
        Csrf csrf = csrf();
        MockHttpServletResponse response = mvc.perform(request.cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andReturn().getResponse();
        return response.getStatus();
    }

    private JsonNode stars(Cookie session) throws Exception {
        JsonNode body = json.readTree(mvc.perform(get("/me/gif-favorites").cookie(session)).andReturn().getResponse()
                .getContentAsString());
        return body.path("gifIds");
    }

    @Test
    void starsAreAPersonalSetOfLibraryGifsNewestFirst() throws Exception {
        UUID id = UUID.randomUUID();
        Cookie session = player(id);
        String first = gifs.catalog().gifs().get(0).id();
        String second = gifs.catalog().gifs().get(1).id();
        assertThat(mvc.perform(get("/me/gif-favorites")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(send(put("/me/gif-favorites/{id}", first), session)).isEqualTo(204);
        // Starring again changes nothing.
        assertThat(send(put("/me/gif-favorites/{id}", first), session)).isEqualTo(204);
        assertThat(send(put("/me/gif-favorites/{id}", second), session)).isEqualTo(204);
        assertThat(send(put("/me/gif-favorites/{id}", "not-a-gif"), session)).isEqualTo(404);
        assertThat(stars(session)).extracting(JsonNode::asText).containsExactly(second, first);
        assertThat(send(delete("/me/gif-favorites/{id}", second), session)).isEqualTo(204);
        assertThat(send(delete("/me/gif-favorites/{id}", second), session)).isEqualTo(204);
        assertThat(stars(session)).extracting(JsonNode::asText).containsExactly(first);
    }

    @Test
    void starsAreCapped() throws Exception {
        UUID id = UUID.randomUUID();
        Cookie session = player(id);
        for (int i = 0; i < GifFavoritesController.MAX; i++) {
            jdbc.update("INSERT INTO gif_favorites (user_id, gif_id) VALUES (?, ?)", id, "old" + i);
        }
        String gif = gifs.catalog().gifs().get(0).id();
        assertThat(send(put("/me/gif-favorites/{id}", gif), session)).isEqualTo(409);
        jdbc.update("DELETE FROM gif_favorites WHERE user_id = ? AND gif_id = 'old0'", id);
        assertThat(send(put("/me/gif-favorites/{id}", gif), session)).isEqualTo(204);
        // Stars for GIFs no longer in the library aren't listed.
        assertThat(stars(session)).extracting(JsonNode::asText).containsExactly(gif);
    }
}
