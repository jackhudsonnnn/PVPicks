package com.p2picks.app.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;

class UserIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private UserLookupController userLookup;

    @Autowired
    private UserProfileService userProfiles;

    @Autowired
    private UserSearchController userSearch;

    @Test
    void userSearchFiltersVisibilityAndPagesByUsername() throws Exception {
        UUID viewer = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'find_viewer', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'findx00', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, UUID.randomUUID(), hash);
        List<UUID> users = new java.util.ArrayList<>();
        for (int i = 0; i < 55; i++) {
            UUID id = UUID.randomUUID();
            users.add(id);
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 2, ?, 'PLAYER', 'ACTIVE')
                    """, id, "find_" + String.format("%02d", i), hash);
        }
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", users.get(10));
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, users.get(22));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, users.get(20), users.get(21), viewer);

        mvc.perform(get("/users").param("query", "find_")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("find_viewer");
        MvcResult response = mvc.perform(get("/users").cookie(session).param("query", "  FIND_  "))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode page = json.readTree(response.getResponse().getContentAsString());
        assertThat(page.path("items").size()).isEqualTo(50);
        assertThat(page.path("items").get(0).path("username").asText()).isEqualTo("find_00");
        assertThat(page.path("items").toString()).contains(users.get(10).toString())
                .doesNotContain(viewer.toString(), users.get(20).toString(), users.get(21).toString(),
                        users.get(22).toString(), "findx00", "status");
        for (JsonNode user : page.path("items")) {
            assertThat(OpenApiSchemas.load("User").validate(user)).isEmpty();
        }
        JsonNode substring = json.readTree(mvc.perform(get("/users").cookie(session).param("query", "ND_10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(substring.path("items").size()).isEqualTo(1);
        assertThat(substring.path("items").get(0).path("id").asText()).isEqualTo(users.get(10).toString());
        JsonNode empty = json.readTree(mvc.perform(get("/users").cookie(session).param("query", "no_such_user"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("nextCursor").isNull()).isTrue();
        String cursor = page.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", viewer, users.get(53));
        page = json.readTree(mvc.perform(get("/users").cookie(session)
                        .param("query", "find_").param("cursor", cursor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("username").asText()).isEqualTo("find_54");
        assertThat(page.path("nextCursor").isNull()).isTrue();
        mvc.perform(get("/users").cookie(session)).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", " ")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find%")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "abcdefghijklmnop")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find_", "other")).andExpect(status().isBadRequest());
        mvc.perform(get("/users").cookie(session).param("query", "find_").param("cursor", "%%%"))
                .andExpect(status().isBadRequest());
        Jwt validatedBeforeRevocation = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
        params.add("query", "find_");
        assertThat(userSearch.search(validatedBeforeRevocation, params).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void userLookupRespectsBlocksAndHistoricalConnections() throws Exception {
        String hash = passwords.encode("a secure test password");
        var ids = new java.util.LinkedHashMap<String, UUID>();
        for (String name : List.of("lkviewer", "lkout", "lkboth", "lkin", "lkshared", "lkreceipt",
                "lkfrozen", "lkdelactive", "lkdelarchive", "lkdelold", "lkdelreceipt", "lkdelunrel",
                "lkdelchat", "lkdelmsg")) {
            UUID id = UUID.randomUUID();
            ids.put(name, id);
            jdbc.update("""
                    INSERT INTO users (id, username, hue, password_hash, role, status)
                    VALUES (?, ?, 2, ?, 'PLAYER', 'ACTIVE')
                    """, id, name, hash);
        }
        UUID viewer = ids.get("lkviewer");
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", ids.get("lkfrozen"));
        for (String name : List.of("lkdelactive", "lkdelarchive", "lkdelold", "lkdelreceipt", "lkdelunrel",
                "lkdelchat", "lkdelmsg")) {
            jdbc.update("""
                    UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                        password_hash = NULL WHERE id = ?
                    """, ids.get(name));
        }
        jdbc.update("""
                INSERT INTO blocks (blocker_id, blocked_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, viewer, ids.get("lkout"), viewer, ids.get("lkboth"), ids.get("lkboth"), viewer,
                ids.get("lkin"), viewer, ids.get("lkshared"), viewer, ids.get("lkreceipt"), viewer);
        UUID active = UUID.randomUUID();
        UUID archive = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, archived_at)
                VALUES (?, 'Lookup active', 'T', 'WK2AA2', NULL),
                       (?, 'Lookup archive', 'T', 'WK2AA3', now() - interval '1 day'),
                       (?, 'Lookup expired', 'T', 'WK2AA4', now() - interval '91 days')
                """, active, archive, expired);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, active, viewer, active, ids.get("lkshared"), active, ids.get("lkdelactive"));
        jdbc.update("""
                INSERT INTO table_archive_members (table_id, user_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?)
                """, archive, viewer, archive, ids.get("lkdelarchive"),
                expired, viewer, expired, ids.get("lkdelold"));
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlement, expired, viewer);
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, 100), (?, ?, ?, ?, ?, ?, 100)
                """, UUID.randomUUID(), settlement, expired, viewer, viewer, ids.get("lkreceipt"),
                UUID.randomUUID(), settlement, expired, viewer, viewer, ids.get("lkdelreceipt"));
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Test?', '["Yes","No"]'::jsonb, 0, true, now())
                """, bet, active, ids.get("lkdelchat"));
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id, created_at)
                VALUES (?, ?, 'bet', ?, ?, now())
                """, UUID.randomUUID(), active, ids.get("lkdelchat"), bet);
        UUID archiveSettlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)",
                archiveSettlement, archive, ids.get("lkout"));
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                VALUES (?, ?, ?, ?, ?, ?, 100)
                """, UUID.randomUUID(), archiveSettlement, archive, ids.get("lkout"), ids.get("lkout"), ids.get("lkdelmsg"));
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, settlement_id, created_at)
                VALUES (?, ?, 'settlement', ?, now())
                """, UUID.randomUUID(), archive, archiveSettlement);

        mvc.perform(get("/users/" + ids.get("lkout"))).andExpect(status().isUnauthorized());
        Cookie session = loginAs("lkviewer");
        for (String name : List.of("lkviewer", "lkout", "lkboth", "lkshared", "lkreceipt", "lkfrozen")) {
            MvcResult response = mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isOk()).andReturn();
            assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            JsonNode identity = json.readTree(response.getResponse().getContentAsString());
            assertThat(OpenApiSchemas.load("User").validate(identity)).isEmpty();
            assertThat(identity.path("username").asText()).isEqualTo(name);
        }
        for (String name : List.of("lkdelactive", "lkdelarchive", "lkdelreceipt", "lkdelchat", "lkdelmsg")) {
            JsonNode identity = json.readTree(mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(OpenApiSchemas.load("DeletedUser").validate(identity)).isEmpty();
            assertThat(identity.path("id").asText()).isEqualTo(ids.get(name).toString());
        }
        String missing = mvc.perform(get("/users/" + UUID.randomUUID()).cookie(session))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        for (String name : List.of("lkin", "lkdelold", "lkdelunrel")) {
            String hidden = mvc.perform(get("/users/" + ids.get(name)).cookie(session))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(hidden).isEqualTo(missing);
        }
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", active, ids.get("lkshared"));
        mvc.perform(get("/users/" + ids.get("lkshared")).cookie(session)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() - interval '91 days' WHERE id = ?", archive);
        mvc.perform(get("/users/" + ids.get("lkdelarchive")).cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/users/" + ids.get("lkdelmsg")).cookie(session)).andExpect(status().isNotFound());
        Jwt validatedBeforeRevocation = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(userLookup.get(validatedBeforeRevocation, ids.get("lkout")).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void userProfileUpdatesNormalizeAndPreserveSessionsAndRetrySafety() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profileuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profiletaken', 2, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Profiles', 'T', 'U8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, user);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', 'A member is now a host', 'host_promoted', ?)
                """, UUID.randomUUID(), table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', ?)",
                UUID.randomUUID(), table, "profileuser joined the table");
        Cookie firstSession = loginAs("profileuser");
        Cookie secondSession = loginAs("profileuser");
        Csrf csrf = csrf();
        for (String invalid : List.of("{}", "{\"username\":null}", "{\"hue\":true}", "{\"hue\":1.0}",
                "{\"hue\":0}", "{\"hue\":6}", "{\"username\":\"ab\"}", "{\"username\":\"a.b\"}",
                "{\"username\":\"abc\\u0000\"}", "{\"username\":\"abc\\uD800\"}", "{\"extra\":1}")) {
            mvc.perform(userProfilePatch(firstSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/me/profile").cookie(firstSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "missing-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hue\":3}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/me/profile").cookie(firstSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hue\":3}"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"PROFILETAKEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"\\u00a0New_Name\\uFEFF\",\"hue\":5}"))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(userProfilePatch(firstSession, csrf, "taken",
                "{\"hue\":5,\"username\":\"new_name\"}")).andExpect(status().isOk()).andReturn();
        JsonNode profile = json.readTree(result.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("User").validate(profile)).isEmpty();
        assertThat(profile.path("username").asText()).isEqualTo("new_name");
        assertThat(profile.path("hue").asInt()).isEqualTo(5);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(mvc.perform(get("/me").cookie(secondSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("username").asText()).isEqualTo("new_name");
        JsonNode chat = chatWindow(table, firstSession, null, null).path("items");
        assertThat(chat.get(0).path("text").asText()).isEqualTo("new_name is now a host");
        assertThat(chat.get(1).path("text").asText()).isEqualTo("profileuser joined the table");
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"hue\":4}")).andExpect(status().isConflict());
        mvc.perform(userProfilePatch(firstSession, csrf, "hue", "{\"hue\":3}")).andExpect(status().isOk());
        MvcResult replay = mvc.perform(userProfilePatch(firstSession, csrf, "taken",
                "{\"username\":\"new_name\",\"hue\":5}")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("hue").asInt()).isEqualTo(3);
        mvc.perform(userProfilePatch(firstSession, csrf, "noop", "{\"hue\":3}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'user.profile.changed.v1'
                    AND payload = jsonb_build_object('userId', ?::text)
                """, Integer.class, user.toString(), user.toString())).isEqualTo(2);
        Csrf loginCsrf = csrf();
        mvc.perform(post("/auth/login").cookie(loginCsrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", loginCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"profileuser\",\"password\":\"a secure test password\"}"))
                .andExpect(status().isUnauthorized());
        loginAs("\u00a0NEW_NAME\uFEFF");
        jdbc.update("UPDATE users SET username = 'profileuser' WHERE id = ?", other);
        assertThat(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class, other))
                .isEqualTo("profileuser");
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        mvc.perform(userProfilePatch(firstSession, csrf, "taken", "{\"username\":\"new_name\",\"hue\":5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentUserProfilePartialUpdatesPreserveBothChanges() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profilpart', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt first = decoder.decode(loginAs("profilpart").getValue());
        Jwt second = decoder.decode(loginAs("profilpart").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return userProfiles.update(first, new UserProfileService.Changes("profilnew", null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return userProfiles.update(second, new UserProfileService.Changes(null, 5), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(UserProfileService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(UserProfileService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT username = 'profilnew'::citext AND hue = 5 FROM users WHERE id = ?",
                    Boolean.class, user)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentUserProfileUpdatesEnforceUniqueUsernames() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profilraceone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profilracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        Jwt one = decoder.decode(loginAs("profilraceone").getValue());
        Jwt two = decoder.decode(loginAs("profilracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> updateOne = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    userProfiles.update(one, new UserProfileService.Changes("raced_name", null), "one");
                    return true;
                } catch (UserProfileService.UsernameTaken exception) {
                    return false;
                }
            };
            java.util.concurrent.Callable<Boolean> updateTwo = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    userProfiles.update(two, new UserProfileService.Changes("raced_name", null), "two");
                    return true;
                } catch (UserProfileService.UsernameTaken exception) {
                    return false;
                }
            };
            var firstResult = executor.submit(updateOne);
            var secondResult = executor.submit(updateTwo);
            start.countDown();
            assertThat(List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'raced_name'::citext",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void userProfileExpiryRollsBackUserOutboxAndRetryClaim() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profileexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt validated = decoder.decode(loginAs("profileexpiry").getValue());
        verifyMutationExpiry(validated,
                jwt -> userProfiles.update(jwt, new UserProfileService.Changes("afterexpiry", 5), "expiry"));
        assertThat(jdbc.queryForObject("SELECT username::text FROM users WHERE id = ?", String.class, user))
                .isEqualTo("profileexpiry");
        assertThat(jdbc.queryForObject("SELECT hue FROM users WHERE id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, user.toString()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isZero();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder userProfilePatch(
            Cookie session, Csrf csrf, String key, String body) {
        return patch("/me/profile").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
