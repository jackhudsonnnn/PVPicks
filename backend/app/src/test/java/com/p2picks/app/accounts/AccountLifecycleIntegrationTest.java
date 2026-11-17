package com.p2picks.app.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Moderation strikes and freezing, host recovery, and deleting your own account. */
class AccountLifecycleIntegrationTest extends IntegrationTestSupport {
    private final UUID admin = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID early = UUID.randomUUID();
    private final UUID late = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();

    private void seed(String prefix) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'ADMIN', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'),
                (?, ?, 2, ?, 'PLAYER', 'ACTIVE'), (?, ?, 3, ?, 'PLAYER', 'ACTIVE')
                """, admin, prefix + "adm", hash, host, prefix + "host", hash, early, prefix + "early", hash,
                late, prefix + "late", hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Lifecycle', 'T', ?)", table,
                code());
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, joined_at) VALUES
                (?, ?, 'HOST', now() - interval '3 days'), (?, ?, 'MEMBER', now() - interval '2 days'),
                (?, ?, 'MEMBER', now() - interval '1 day')
                """, table, host, table, early, table, late);
    }

    private static String code() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return code.toString();
    }

    private UUID report(UUID reporter, UUID target) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reports (id, reporter_id, target_id, reason, note) VALUES (?, ?, ?, 'spam', '')",
                id, reporter, target);
        return id;
    }

    private MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request, Cookie session, Csrf csrf) {
        return request.cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private String role(UUID user) {
        return jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?", String.class,
                table, user);
    }

    @Test
    void threeStrikesFreezeTheAccountEndItsSessionsAndPassItsTablesOn() throws Exception {
        seed("ml");
        Cookie adminSession = loginAs("mladm");
        Cookie hostSession = loginAs("mlhost");
        Csrf csrf = csrf();
        // One open report per reporter per target, so each reporter's next report waits until the last is handled.
        UUID first = report(early, host);
        UUID dismissed = report(late, host);

        mvc.perform(get("/admin/reports").cookie(hostSession)).andExpect(status().isForbidden());
        JsonNode page = json.readTree(mvc.perform(get("/admin/reports").cookie(adminSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode queue = page.path("items");
        assertThat(queue.size()).isGreaterThanOrEqualTo(2);
        assertThat(queue.toString()).contains("mlhost");
        var reportSchema = OpenApiSchemas.load("ModerationReport");
        queue.forEach(report -> assertThat(reportSchema.validate(report)).isEmpty());

        mvc.perform(mutate(post("/admin/reports/" + dismissed + "/dismiss"), adminSession, csrf))
                .andExpect(status().isNoContent());
        mvc.perform(mutate(post("/admin/reports/" + dismissed + "/strike"), adminSession, csrf))
                .andExpect(status().isConflict());
        mvc.perform(mutate(post("/admin/reports/" + first + "/strike"), adminSession, csrf))
                .andExpect(status().isOk());
        UUID second = report(late, host);
        mvc.perform(mutate(post("/admin/reports/" + second + "/strike"), adminSession, csrf))
                .andExpect(status().isOk());
        UUID third = report(early, host);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, host))
                .isEqualTo("ACTIVE");
        JsonNode freeze = json.readTree(mvc.perform(mutate(post("/admin/reports/" + third + "/strike"), adminSession,
                csrf)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(freeze.path("strikes").asInt()).isEqualTo(3);
        assertThat(freeze.path("frozen").asBoolean()).isTrue();

        // Frozen: sessions end, sign-in says why, and the earliest-joined active member hosts the table now.
        mvc.perform(get("/me").cookie(hostSession)).andExpect(status().isUnauthorized());
        Csrf login = csrf();
        JsonNode refused = json.readTree(mvc.perform(post("/auth/login").header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", login.token()).cookie(login.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mlhost\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString());
        assertThat(refused.path("code").asText()).isEqualTo("account_frozen");
        assertThat(role(early)).isEqualTo("HOST");
        assertThat(role(late)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM chat_messages WHERE table_id = ? AND system_event = 'host_promoted'
                  AND system_subject_id = ?
                """, Integer.class, table, early)).isEqualTo(1);
        JsonNode flagged = json.readTree(mvc.perform(get("/admin/users/flagged").cookie(adminSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(flagged.path("items").toString()).contains("\"status\":\"FROZEN\"").contains("\"strikes\":3");

        // Unfreezing restores sign-in, not hosting; clearing strikes doesn't touch the status.
        mvc.perform(mutate(post("/admin/users/" + host + "/clear-strikes"), adminSession, csrf))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, host))
                .isEqualTo("FROZEN");
        mvc.perform(mutate(post("/admin/users/" + host + "/unfreeze"), adminSession, csrf))
                .andExpect(status().isNoContent());
        loginAs("mlhost");
        assertThat(role(host)).isEqualTo("HOST");
    }

    @Test
    void deletingAnAccountWaitsUntilItsSquareThenRemovesPersonalData() throws Exception {
        seed("dl");
        Cookie hostSession = loginAs("dlhost");
        Csrf csrf = csrf();
        UUID bet = insertDebtBet(table, host, early, "PENDING", false, true, false, 100, 0);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)", host, early, early, host);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'hi')",
                UUID.randomUUID(), table, host);

        mvc.perform(mutate(post("/me/delete"), hostSession, csrf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"not it\"}")).andExpect(status().isForbidden());
        JsonNode blocked = json.readTree(mvc.perform(mutate(post("/me/delete"), hostSession, csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"a secure test password\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
        assertThat(blocked.path("blockers").toString()).contains("open_bets");

        jdbc.update("UPDATE bets SET status = 'WASHED', closed_at = now() WHERE id = ?", bet);
        mvc.perform(mutate(post("/me/delete"), hostSession, csrf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a secure test password\"}")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT status || ':' || (username IS NULL) FROM users WHERE id = ?",
                String.class, host)).isEqualTo("DELETED:true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follows WHERE follower_id = ? OR followee_id = ?",
                Integer.class, host, host)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE author_id = ? AND kind = 'text'",
                Integer.class, host)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id = ?", Integer.class, bet))
                .as("financial history stays").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE user_id = ?", Integer.class, host))
                .isZero();
        assertThat(role(early)).as("the earliest-joined member hosts now").isEqualTo("HOST");
        mvc.perform(get("/me").cookie(hostSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void aTableWithNobodyActiveLeftIsArchived() throws Exception {
        seed("al");
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id IN (?, ?)", early, late);
        Cookie hostSession = loginAs("alhost");
        Csrf csrf = csrf();
        mvc.perform(mutate(post("/me/delete"), hostSession, csrf).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"a secure test password\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM tables WHERE id = ?", Boolean.class,
                table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_archive_members WHERE table_id = ?",
                Integer.class, table)).as("the frozen members can still read the archive").isEqualTo(2);
    }

    @Test
    void wrongPasswordsOnDeletionShareTheSignInThrottle() throws Exception {
        seed("dt");
        Cookie hostSession = loginAs("dthost");
        Csrf csrf = csrf();
        String address = "10.77." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
        for (int i = 0; i < 10; i++) {
            mvc.perform(mutate(post("/me/delete"), hostSession, csrf).contentType(MediaType.APPLICATION_JSON)
                    .with(request -> {
                        request.setRemoteAddr(address);
                        return request;
                    }).content("{\"password\":\"guess " + i + "\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(mutate(post("/me/delete"), hostSession, csrf).contentType(MediaType.APPLICATION_JSON)
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }).content("{\"password\":\"a secure test password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, host))
                .as("refused even with the right password").isEqualTo("ACTIVE");
    }

    /** Walks an admin list's pages; each holds at most 50 and no row repeats. */
    private List<String> walk(Cookie admin, String path) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        String cursor = null;
        do {
            String url = path + (cursor == null ? "" : (path.contains("?") ? "&" : "?") + "cursor=" + cursor);
            JsonNode page = json.readTree(mvc.perform(get(url).cookie(admin)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(page.path("items").size()).isLessThanOrEqualTo(50);
            page.path("items").forEach(item -> ids.add(item.path("id").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(ids).doesNotHaveDuplicates();
        return ids;
    }

    @Test
    void adminListsPageFiftyAtATime() throws Exception {
        seed("pg");
        Cookie adminSession = loginAs("pgadm");
        for (int i = 0; i < 55; i++) {
            jdbc.update("""
                    INSERT INTO reports (id, reporter_id, target_id, reason, note, status, created_at)
                    VALUES (?, ?, ?, 'spam', '', 'RESOLVED', now() - make_interval(secs => ?))
                    """, UUID.randomUUID(), early, host, i);
        }
        List<String> reports = walk(adminSession, "/admin/reports?status=all");
        assertThat(reports).hasSize(jdbc.queryForObject("SELECT count(*) FROM reports", Integer.class))
                .hasSizeGreaterThan(50);
        List<String> players = walk(adminSession, "/admin/players");
        assertThat(players).hasSize(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'PLAYER' AND status <> 'DELETED'", Integer.class));
        jdbc.update("""
                INSERT INTO admin_user_flags (user_id, strike_count) VALUES (?, 2), (?, 1)
                ON CONFLICT (user_id) DO UPDATE SET strike_count = EXCLUDED.strike_count
                """, host, early);
        List<String> flagged = walk(adminSession, "/admin/users/flagged");
        assertThat(flagged.indexOf(host.toString())).as("more strikes first")
                .isLessThan(flagged.indexOf(early.toString()));

        for (String path : List.of("/admin/reports?cursor=nope", "/admin/players?cursor=bm9wZQ",
                "/admin/users/flagged?cursor=YXxifGM")) {
            mvc.perform(get(path).cookie(adminSession)).andExpect(status().isBadRequest());
        }
    }
}
