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
    @org.springframework.beans.factory.annotation.Autowired
    private LoginThrottle throttle;

    @Test
    void failedSignInsAreThrottledPerAccountAndPerAddress() throws Exception {
        String name = "thr" + Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30), 36);
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 1, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), name, passwords.encode("a secure test password"));
        // Unique addresses, so this test doesn't spend the shared 127.0.0.1 budget other tests sign in from.
        String home = "198.51.100." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
        String elsewhere = "203.0.113." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);

        assertThat(signIn(name, "wrong", home).getStatus()).isEqualTo(401);
        assertThat(signIn(name, "a secure test password", home).getStatus())
                .as("a success clears the account's failures").isEqualTo(204);
        for (int i = 0; i < 9; i++) throttle.failed(name, home);
        assertThat(signIn(name, "a secure test password", home).getStatus()).isEqualTo(204);

        for (int i = 0; i < 10; i++) throttle.failed(" " + name.toUpperCase() + " ", home);
        var blocked = signIn(name, "a secure test password", elsewhere);
        assertThat(blocked.getStatus()).as("the account limit applies from any address").isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("900");
        JsonNode body = json.readTree(blocked.getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo("rate_limited");
        assertThat(OpenApiSchemas.load("Error").validate(body)).isEmpty();

        String other = "thr2" + Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 30), 36);
        jdbc.update("INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, 1, ?, 'ADMIN', 'ACTIVE')",
                UUID.randomUUID(), other, passwords.encode("a secure test password"));
        assertThat(signIn(other, "a secure test password", home).getStatus()).isEqualTo(204);
        for (int i = 0; i < 50; i++) throttle.failed("spray" + i, home);
        assertThat(signIn(other, "a secure test password", home).getStatus())
                .as("the address limit applies to every account").isEqualTo(429);
        assertThat(signIn(other, "a secure test password", elsewhere).getStatus()).isEqualTo(204);
    }

    private org.springframework.mock.web.MockHttpServletResponse signIn(String username, String password, String address)
            throws Exception {
        Csrf csrf = csrf();
        return mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).cookie(csrf.cookie())
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse();
    }
    @Test
    void loginRequiresOriginAndCsrfAndLogoutRevokesSession() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "login_user", 2, passwords.encode("a secure test password"), "ADMIN", "ACTIVE");
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
        assertThat(current.path("role").asText()).isEqualTo("ADMIN");
        assertThat(OpenApiSchemas.load("CurrentUser").validate(current)).isEmpty();
        Cookie tampered = new Cookie("session", "x" + session.getValue().substring(1));
        // A session the server won't accept is cleared from the browser, and never blocks the public config.
        MvcResult rejected = mvc.perform(get("/me").cookie(tampered)).andExpect(status().isUnauthorized()).andReturn();
        assertThat(rejected.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header).startsWith("session=").contains("Max-Age=0"));
        mvc.perform(get("/config").cookie(tampered)).andExpect(status().isOk());
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

    @org.springframework.beans.factory.annotation.Autowired
    Sessions sessions;

    @Test
    void passwordlessSignInIssuesAWorkingSessionButNotForFrozenOrMissingAccounts() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "pwless_user", 1, passwords.encode("unused password"), "PLAYER", "ACTIVE");
        String token = sessions.signInWithoutPassword(userId, null);
        mvc.perform(get("/me").cookie(new Cookie("session", token))).andExpect(status().isOk());
        assertThat(sessions.signInWithoutPassword(UUID.randomUUID(), null)).isNull();
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", userId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.signInWithoutPassword(userId, null))
                .isInstanceOf(Sessions.Frozen.class);
        // The demo's picker endpoints exist only with the demo on; here they're open but absent. A stale session
        // cookie (e.g. after a demo reset wiped sessions) doesn't turn them into a 401.
        mvc.perform(get("/dev/demo/accounts")).andExpect(status().isNotFound());
        mvc.perform(get("/dev/demo/accounts").cookie(new Cookie("session", "stale.token.value")))
                .andExpect(status().isNotFound());
        // The Dev page works signed out in dev: with dev data off here, its paths are absent, not unauthorized.
        mvc.perform(get("/dev/sports")).andExpect(status().isNotFound());
        mvc.perform(get("/dev/demo")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsWrongCredentialsAndDisablesFrozenAndExpiredSessions() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, hue, password_hash, role, status) VALUES (?, ?, ?, ?, ?, ?)",
                userId, "frozen_user", 1, passwords.encode("a secure test password"), "ADMIN", "ACTIVE");
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
                .andExpect(status().isForbidden()).andReturn();
        // Only the right password reveals the freeze; a wrong one looks like any unknown account.
        assertThat(json.readTree(frozen.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("account_frozen");
        MvcResult guessed = mvc.perform(post("/auth/login").header("Origin", origin)
                        .header("X-CSRF-TOKEN", frozenCsrf.token()).cookie(frozenCsrf.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"frozen_user\",\"password\":\"not the password\"}"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(guessed.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
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

        Cookie session = loginAs("roleplayer");
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
