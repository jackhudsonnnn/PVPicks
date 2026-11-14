package com.p2picks.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;

class AuthIntegrationTest extends IntegrationTestSupport {
    @Test
    void loginRequiresOriginAndCsrfAndLogoutRevokesSession() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "login_user", 2, passwords.encode("a secure test password"), "PLAYER", "ACTIVE");
        Csrf csrf = csrf();
        String login = "{\"username\":\" LOGIN_USER \",\"password\":\"a secure test password\"}";
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://evil.example")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .cookie(csrf.cookie()).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"login_user\",\"password\":\"a secure test password\",\"extra\":true}"))
                .andExpect(status().isBadRequest());
        MvcResult loggedIn = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = loggedIn.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        Jwt jwt = decoder.decode(session.getValue());
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(java.time.Duration.ofHours(8));
        assertThat(loggedIn.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).contains("SameSite=Strict", "Max-Age=28800"));
        MvcResult me = mvc.perform(get("/me").cookie(session)).andExpect(status().isOk()).andReturn();
        JsonNode current = json.readTree(me.getResponse().getContentAsString());
        assertThat(current.path("username").asText()).isEqualTo("login_user");
        assertThat(current.path("role").asText()).isEqualTo("PLAYER");
        assertThat(OpenApiSchemas.load("CurrentUser").validate(current)).isEmpty();
        Cookie tampered = new Cookie("session", "x" + session.getValue().substring(1));
        mvc.perform(get("/me").cookie(tampered)).andExpect(status().isUnauthorized());
        Csrf logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(session, logoutCsrf.cookie()))
                .andExpect(status().isForbidden());
        MvcResult replacement = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()).cookie(session, logoutCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isNoContent()).andReturn();
        Cookie replacementSession = replacement.getResponse().getCookie("session");
        assertThat(replacementSession).isNotNull();
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
        session = replacementSession;
        logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()).cookie(session, logoutCsrf.cookie()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequestsKeepTheCsrfTokenUntilLoginOrLogout() throws Exception {
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, 'csrfkeep', 1, ?, 'PLAYER', 'ACTIVE')",
                UUID.randomUUID(), passwords.encode("a secure test password"));
        Cookie session = loginAs("csrfkeep");
        Csrf csrf = csrf();
        MvcResult read = mvc.perform(get("/me").cookie(session, csrf.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(read.getResponse().getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
        for (String body : new String[] {"{\"capCents\":1000}", "{\"capCents\":2000}"}) {
            MvcResult update = mvc.perform(debtCapPut(session, csrf, UUID.randomUUID().toString(), body))
                    .andExpect(status().isNoContent()).andReturn();
            assertThat(update.getResponse().getHeaders("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
        }
    }

    @Test
    void rejectsWrongCredentialsAndDisablesFrozenAndExpiredSessions() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "frozen_user", 1, passwords.encode("a secure test password"), "PLAYER", "ACTIVE");
        Csrf csrf = csrf();
        String origin = "http://127.0.0.1:8080";
        MvcResult wrong = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknown = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unknown_user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        MvcResult login = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", userId);
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
        Csrf frozenCsrf = csrf();
        MvcResult frozen = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", frozenCsrf.token()).cookie(frozenCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(frozen.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
        jdbc.update("""
                UPDATE auth_sessions SET created_at = now() - interval '1 minute', expires_at = now() - interval '1 second'
                WHERE user_id = ?
                """, userId);
        mvc.perform(get("/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesRequireAnActiveAdministratorAndUseTheCurrentDatabaseRole() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'roleplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'roleadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, userId, passwords.encode("a secure test password"),
                adminId, passwords.encode("a secure test password"));
        mvc.perform(get("/admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/users/" + userId + "/audit")).andExpect(status().isUnauthorized());

        Csrf loginCsrf = csrf();
        MvcResult login = mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", loginCsrf.token()).cookie(loginCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"roleplayer\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isNoContent()).andReturn();
        Cookie session = login.getResponse().getCookie("session");
        assertThat(session).isNotNull();
        mvc.perform(get("/admin").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/health").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/security-probe").cookie(session)).andExpect(status().isForbidden());
        Csrf actionCsrf = csrf();
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tables").cookie(session)).andExpect(status().isOk());

        session = loginAs("roleadmin");
        mvc.perform(get("/admin/security-probe").cookie(session)).andExpect(status().isNoContent());
        JsonNode admin = json.readTree(mvc.perform(get("/me").cookie(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(admin.path("role").asText()).isEqualTo("ADMIN");
        mvc.perform(get("/tables").cookie(session)).andExpect(status().isForbidden());
        mvc.perform(post("/tables").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token())
                        .header("Idempotency-Key", "admin-create")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Blocked\",\"emoji\":\"🏈\"}"))
                .andExpect(status().isForbidden());
        MvcResult health = mvc.perform(get("/admin/health").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(health.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode snapshot = json.readTree(health.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("AdminHealthSnapshot").validate(snapshot)).isEmpty();
        assertThat(snapshot.path("postgres").asText()).isEqualTo("UP");
        assertThat(snapshot.path("redis").asText()).isEqualTo("UP");
        assertThat(snapshot.path("kafka").asText()).isEqualTo("NOT_CHECKED");
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/security-probe").cookie(session, actionCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", actionCsrf.token()))
                .andExpect(status().isNoContent());
        Cookie anotherAdminSession = loginAs("roleadmin");
        Csrf logoutCsrf = csrf();
        mvc.perform(post("/auth/logout").cookie(anotherAdminSession, logoutCsrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", logoutCsrf.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/me").cookie(anotherAdminSession)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", adminId);
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", adminId);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", adminId);
        mvc.perform(get("/admin/users/" + userId + "/audit").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void accountRolesAreImmutableWithoutBlockingOtherUpdates() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'fixedplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'fixedadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, player, hash, admin, hash);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", player))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET role = 'PLAYER' WHERE id = ?", admin))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE users SET role = role, hue = 2 WHERE id = ?", player)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", admin)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, player))
                .isEqualTo("PLAYER");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, admin))
                .isEqualTo("ADMIN");
    }
}
