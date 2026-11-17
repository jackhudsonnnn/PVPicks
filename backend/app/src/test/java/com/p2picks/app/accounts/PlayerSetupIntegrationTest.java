package com.p2picks.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

class PlayerSetupIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final String STRONG = "a long enough passphrase";

    private String name(String prefix) {
        return prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
    }

    private Cookie admin() throws Exception {
        String username = name("adm");
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 4, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), username, passwords.encode("a secure test password"));
        return loginAs(username);
    }

    /** A unique client address per request group, so these tests don't spend the shared throttle budget. */
    private final String address = "192.0.2." + ThreadLocalRandom.current().nextInt(1, 255);

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Cookie session, Object body)
            throws Exception {
        Csrf csrf = csrf();
        request.header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                .with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                })
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (session != null) request.cookie(session);
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse login(String username, String password) throws Exception {
        return send(post("/auth/login"), null, Map.of("username", username, "password", password));
    }

    @Test
    void adminCreatesAPlayerWhoSetsTheirOwnPasswordWithASingleUseCode() throws Exception {
        Cookie admin = admin();
        String username = name("new");
        MockHttpServletResponse created = send(post("/admin/players"), admin, Map.of("username", " " + username.toUpperCase() + " "));
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getContentAsString());
        assertThat(body.path("username").asText()).isEqualTo(username);
        assertThat(body.path("purpose").asText()).isEqualTo("SETUP");
        String code = body.path("setupCode").asText();
        assertThat(code).matches("[A-Za-z0-9_-]{22}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_setup_codes WHERE code_hash = convert_to(?, 'UTF8')",
                Integer.class, code)).as("only a hash is stored").isZero();

        assertThat(send(post("/admin/players"), admin, Map.of("username", username)).getStatus()).isEqualTo(409);
        assertThat(login(username, "").getStatus()).as("no usable password yet").isEqualTo(401);

        MockHttpServletResponse weak = send(post("/auth/setup"), null, Map.of("code", code, "password", "short"));
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(json.readTree(weak.getContentAsString()).path("code").asText()).isEqualTo("weak_password");
        assertThat(send(post("/auth/setup"), null, Map.of("code", code, "password", username + "-is-me-ok"))
                .getStatus()).as("the username isn't allowed").isEqualTo(400);

        MockHttpServletResponse setUp = send(post("/auth/setup"), null, Map.of("code", code, "password", STRONG));
        assertThat(setUp.getStatus()).as("a weak attempt doesn't use up the code").isEqualTo(204);
        Cookie session = setUp.getCookie("session");
        assertThat(session).isNotNull();
        mvc.perform(get("/me").cookie(session)).andExpect(status().isOk());

        MockHttpServletResponse reused = send(post("/auth/setup"), null, Map.of("code", code, "password", STRONG));
        assertThat(reused.getStatus()).isEqualTo(400);
        assertThat(json.readTree(reused.getContentAsString()).path("code").asText()).isEqualTo("invalid_setup_code");
        assertThat(login(username, STRONG).getStatus()).isEqualTo(204);

        JsonNode listed = json.readTree(mvc.perform(get("/admin/players").cookie(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode mine = null;
        for (JsonNode player : listed.path("items")) if (player.path("username").asText().equals(username)) mine = player;
        assertThat(mine).isNotNull();
        assertThat(mine.path("passwordSet").asBoolean()).isTrue();
        assertThat(mine.has("setupCode")).isFalse();

        mvc.perform(get("/admin/players").cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void aResetCodeReplacesTheOldPasswordAndEndsEverySession() throws Exception {
        Cookie admin = admin();
        String username = name("rst");
        JsonNode created = json.readTree(send(post("/admin/players"), admin, Map.of("username", username))
                .getContentAsString());
        UUID player = UUID.fromString(created.path("id").asText());
        Cookie first = send(post("/auth/setup"), null, Map.of("code", created.path("setupCode").asText(),
                "password", STRONG)).getCookie("session");

        JsonNode first_reset = json.readTree(send(post("/admin/players/" + player + "/code"), admin, Map.of())
                .getContentAsString());
        JsonNode reset = json.readTree(send(post("/admin/players/" + player + "/code"), admin, Map.of())
                .getContentAsString());
        assertThat(reset.path("purpose").asText()).isEqualTo("RESET");
        assertThat(send(post("/auth/setup"), null, Map.of("code", first_reset.path("setupCode").asText(),
                "password", "another long passphrase")).getStatus()).as("a newer code cancels the older").isEqualTo(400);

        assertThat(send(post("/auth/setup"), null, Map.of("code", reset.path("setupCode").asText(),
                "password", "another long passphrase")).getStatus()).isEqualTo(204);
        mvc.perform(get("/me").cookie(first)).andExpect(status().isUnauthorized());
        assertThat(login(username, STRONG).getStatus()).isEqualTo(401);
        assertThat(login(username, "another long passphrase").getStatus()).isEqualTo(204);
    }

    @Test
    void playersChangeTheirOwnPasswordWithTheCurrentOne() throws Exception {
        Cookie admin = admin();
        String username = name("chg");
        JsonNode created = json.readTree(send(post("/admin/players"), admin, Map.of("username", username))
                .getContentAsString());
        Cookie here = send(post("/auth/setup"), null, Map.of("code", created.path("setupCode").asText(),
                "password", STRONG)).getCookie("session");
        Cookie elsewhere = login(username, STRONG).getCookie("session");

        MockHttpServletResponse wrong = send(post("/auth/password"), here,
                Map.of("currentPassword", "not my password", "newPassword", "a brand new passphrase"));
        assertThat(wrong.getStatus()).isEqualTo(403);
        assertThat(send(post("/auth/password"), here, Map.of("currentPassword", STRONG, "newPassword", "tiny"))
                .getStatus()).isEqualTo(400);
        assertThat(send(post("/auth/password"), here,
                Map.of("currentPassword", STRONG, "newPassword", "a brand new passphrase")).getStatus()).isEqualTo(204);
        mvc.perform(get("/me").cookie(here)).andExpect(status().isOk());
        mvc.perform(get("/me").cookie(elsewhere)).andExpect(status().isUnauthorized());
        assertThat(login(username, "a brand new passphrase").getStatus()).isEqualTo(204);

        assertThat(send(post("/auth/password"), admin,
                Map.of("currentPassword", "a secure test password", "newPassword", "an admin passphrase!"))
                .getStatus()).as("admins can change theirs too").isEqualTo(204);
    }
}
