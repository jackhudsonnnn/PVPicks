package com.p2picks.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.security.SignInCodes;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Players sign in with a code the admin shows them; only admins have passwords (owner decision, 2026-11-17). */
class SignInCodeIntegrationTest extends IntegrationTestSupport {
    private static final String ORIGIN = "http://127.0.0.1:8080";

    private String name(String prefix) {
        return prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
    }

    private Cookie admin() throws Exception {
        String username = name("adm");
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 4, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), username, passwords.encode("a secure test password"));
        return loginAs(username);
    }

    /** A unique client address per test, so these tests don't spend the shared throttle budget. */
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

    /** Typed sign-in: username and code. */
    private MockHttpServletResponse typed(String username, String code) throws Exception {
        return send(post("/auth/code"), null, Map.of("username", username, "code", code));
    }

    /** A QR link: the player's id and code. */
    private MockHttpServletResponse scanned(UUID player, String code) throws Exception {
        return send(post("/auth/code"), null, Map.of("player", player.toString(), "code", code));
    }

    private JsonNode create(Cookie admin, String username) throws Exception {
        return json.readTree(send(post("/admin/players"), admin, Map.of("username", username)).getContentAsString());
    }

    @Test
    void anAdminCreatedPlayerSignsInWithUsernameAndCodeAndNeverAPassword() throws Exception {
        Cookie admin = admin();
        String username = name("new");
        MockHttpServletResponse created = send(post("/admin/players"), admin,
                Map.of("username", " " + username.toUpperCase() + " "));
        assertThat(created.getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getContentAsString());
        String code = body.path("code").asText();
        assertThat(SignInCodes.normalize(code)).isEqualTo(code);
        UUID player = UUID.fromString(body.path("id").asText());

        // The admin can see it again any time; the list carries when it was rotated, never the code.
        JsonNode shown = json.readTree(mvc.perform(get("/admin/players/" + player + "/code").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(shown.path("code").asText()).isEqualTo(code);
        JsonNode listed = json.readTree(mvc.perform(get("/admin/players").param("q", username).cookie(admin))
                .andReturn().getResponse().getContentAsString()).path("items").get(0);
        assertThat(listed.has("code")).isFalse();
        assertThat(listed.path("codeRotatedAt").isTextual()).isTrue();
        // Stored sealed and hashed, never readable.
        assertThat(jdbc.queryForObject("SELECT position(?::bytea IN sealed) = 0 FROM sign_in_codes WHERE user_id = ?",
                Boolean.class, code.getBytes(), player)).isTrue();

        // Case, spaces and dashes don't matter, in the username or the code.
        String typedCode = code.substring(0, 2).toLowerCase() + " - " + code.substring(2).toLowerCase();
        MockHttpServletResponse signedIn = typed(" " + username.toUpperCase() + " ", typedCode);
        assertThat(signedIn.getStatus()).isEqualTo(204);
        Cookie session = signedIn.getCookie("session");
        JsonNode me = json.readTree(mvc.perform(get("/me").cookie(session)).andReturn().getResponse()
                .getContentAsString());
        assertThat(me.path("username").asText()).isEqualTo(username);
        assertThat(scanned(player, code).getStatus()).as("the QR link's form").isEqualTo(204);

        // The code alone, or with someone else's name, signs nobody in; neither does a malformed body.
        String otherName = name("oth");
        create(admin, otherName);
        MockHttpServletResponse wrongName = typed(otherName, code);
        assertThat(wrongName.getStatus()).isEqualTo(401);
        assertThat(json.readTree(wrongName.getContentAsString()).path("code").asText()).isEqualTo("invalid_code");
        assertThat(send(post("/auth/code"), null, Map.of("code", code)).getStatus()).isEqualTo(400);
        assertThat(send(post("/auth/code"), null, Map.of("username", username, "player", player.toString(),
                "code", code)).getStatus()).isEqualTo(400);

        // No password sign-in for players, and no password to change.
        assertThat(send(post("/auth/login"), null, Map.of("username", username, "password", "!code-only"))
                .getStatus()).isEqualTo(401);
        MockHttpServletResponse change = send(post("/auth/password"), session,
                Map.of("currentPassword", "anything at all", "newPassword", "a brand new passphrase"));
        assertThat(change.getStatus()).isEqualTo(403);
        assertThat(json.readTree(change.getContentAsString()).path("code").asText()).isEqualTo("not_admin");
        assertThat(send(post("/auth/password"), admin,
                Map.of("currentPassword", "a secure test password", "newPassword", "an admin passphrase!"))
                .getStatus()).as("admins change theirs").isEqualTo(204);
    }

    @Test
    void aRenameKeepsTheQrWorkingAndMovesTypedSignInToTheNewName() throws Exception {
        Cookie admin = admin();
        String before = name("ren");
        JsonNode created = create(admin, before);
        UUID player = UUID.fromString(created.path("id").asText());
        String code = created.path("code").asText();
        Cookie session = scanned(player, code).getCookie("session");
        String after = name("now");
        Csrf csrf = csrf();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/me/profile")
                        .cookie(session, csrf.cookie()).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + after + "\"}"))
                .andExpect(status().isOk());
        assertThat(scanned(player, code).getStatus()).as("the photographed QR still works").isEqualTo(204);
        assertThat(typed(after, code).getStatus()).isEqualTo(204);
        assertThat(typed(before, code).getStatus()).as("the old name is nobody now").isEqualTo(401);
    }

    @Test
    void rotatingIssuesANewCodeAndSignsThePlayerOutEverywhere() throws Exception {
        Cookie admin = admin();
        String username = name("rot");
        JsonNode created = create(admin, username);
        UUID player = UUID.fromString(created.path("id").asText());
        String old = created.path("code").asText();
        Cookie phone = scanned(player, old).getCookie("session");
        Cookie laptop = typed(username, old).getCookie("session");

        MockHttpServletResponse rotated = send(post("/admin/players/" + player + "/code"), admin, Map.of());
        assertThat(rotated.getStatus()).isEqualTo(200);
        String fresh = json.readTree(rotated.getContentAsString()).path("code").asText();
        assertThat(fresh).isNotEqualTo(old);
        mvc.perform(get("/me").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/me").cookie(laptop)).andExpect(status().isUnauthorized());
        assertThat(scanned(player, old).getStatus()).isEqualTo(401);
        assertThat(scanned(player, fresh).getStatus()).isEqualTo(204);
        // The phone still holds its revoked session cookie; that mustn't block signing in with the new code.
        MockHttpServletResponse back = send(post("/auth/code"), phone,
                Map.of("player", player.toString(), "code", fresh));
        assertThat(back.getStatus()).as("a stale session doesn't block the new code").isEqualTo(204);
        mvc.perform(get("/me").cookie(back.getCookie("session"))).andExpect(status().isOk());

        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", player);
        MockHttpServletResponse frozen = typed(username, fresh);
        assertThat(frozen.getStatus()).isEqualTo(403);
        assertThat(json.readTree(frozen.getContentAsString()).path("code").asText()).isEqualTo("account_frozen");

        mvc.perform(get("/admin/players/" + UUID.randomUUID() + "/code").cookie(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/players/" + player + "/code").cookie(phone))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCodesLockOnlyThatPlayerAndTheAdminCanUnlockThem() throws Exception {
        Cookie admin = admin();
        String targetName = name("thc");
        JsonNode target = create(admin, targetName);
        UUID player = UUID.fromString(target.path("id").asText());
        String code = target.path("code").asText();
        String bystanderName = name("bys");
        String bystanderCode = create(admin, bystanderName).path("code").asText();

        // An unknown username and a wrong code get the same answer.
        MockHttpServletResponse unknown = typed(name("nobody"), code);
        MockHttpServletResponse wrong = typed(targetName, "!!!!");
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(unknown.getContentAsString()).isEqualTo(wrong.getContentAsString());
        for (int i = 0; i < 9; i++) typed(targetName, "????");
        MockHttpServletResponse blocked = scanned(player, code);
        assertThat(blocked.getStatus()).as("even the right code (and QR) waits").isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("900");
        assertThat(typed(bystanderName, bystanderCode).getStatus()).as("other players are unaffected")
                .isEqualTo(204);

        // The admin sees who's locked, and unlocks them.
        JsonNode lockouts = json.readTree(mvc.perform(get("/admin/sign-in-lockout").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("SignInLockouts").validate(lockouts)).isEmpty();
        assertThat(lockouts.path("playerLimit").asInt()).isEqualTo(10);
        JsonNode entry = null;
        for (JsonNode row : lockouts.path("players")) if (row.path("id").asText().equals(player.toString())) entry = row;
        assertThat(entry).isNotNull();
        assertThat(entry.path("username").asText()).isEqualTo(targetName);
        assertThat(entry.path("locked").asBoolean()).isTrue();
        assertThat(entry.path("wrongCodes").asInt()).isEqualTo(10);
        assertThat(entry.path("resetsAt").isTextual()).isTrue();
        assertThat(send(delete("/admin/sign-in-lockout/players/" + player), admin, Map.of()).getStatus())
                .isEqualTo(204);
        Cookie session = scanned(player, code).getCookie("session");
        assertThat(session).as("unlocked at once").isNotNull();
        assertThat(send(delete("/admin/sign-in-lockout"), admin, Map.of()).getStatus()).isEqualTo(204);
        mvc.perform(get("/admin/sign-in-lockout").cookie(session)).andExpect(status().isForbidden());
    }
}
