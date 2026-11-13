package com.p2picks.app.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.chat.ChatSendService;
import com.p2picks.app.security.MutationAuthentication;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class TableSettingsIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private ChatSendService chatSend;

    @Autowired
    private TableMemberRemovalService tableMemberRemovals;

    @Autowired
    private TablePermissionService tablePermissions;

    @Autowired
    private TableProfileService tableProfile;

    @Autowired
    private TableQuickPickService tableQuickPicks;

    @Autowired
    private TableRemainderService tableRemainder;

    @Autowired
    private TableSettingsService tableSettings;

    @Test
    void hostPermissionsAreQuietRetrySafeAndEnforcedByChat() throws Exception {
        UUID host = UUID.randomUUID();
        UUID otherHost = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permhostpeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permobserver', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, otherHost, hash, member, hash, observer, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Permission', 'T', 'P4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, otherHost, table, member, table, observer);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, host);
        Cookie hostSession = loginAs("permhost");
        Cookie memberSession = loginAs("permmember");
        Cookie observerSession = loginAs("permobserver");
        Cookie outsideSession = loginAs("permoutside");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(hostSession.getValue());
        String disableText = "{\"permission\":\"text\",\"enabled\":false}";
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON).content(disableText))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(disableText)).andExpect(status().isForbidden());
        mvc.perform(put("/tables/" + table + "/members/" + member + "/permissions").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(disableText)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"permission\":\"text\"}", "{\"permission\":\"text\",\"enabled\":\"true\"}",
                "{\"permission\":\"bad\",\"enabled\":true}", "{\"permission\":\"text\",\"enabled\":true,\"extra\":1}")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(permissionPut(table, member, outsideSession, csrf, "outside", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, observer, memberSession, csrf, "non-host", disableText))
                .andExpect(status().isForbidden());
        mvc.perform(permissionPut(table, outsider, hostSession, csrf, "not-member", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, UUID.randomUUID(), hostSession, csrf, "missing", disableText))
                .andExpect(status().isNotFound());
        mvc.perform(permissionPut(table, host, hostSession, csrf, "self", disableText)).andExpect(status().isConflict());
        mvc.perform(permissionPut(table, otherHost, hostSession, csrf, "other-host", disableText))
                .andExpect(status().isConflict());
        MvcResult result = mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(chatSendPost(table, memberSession, csrf, "text-blocked", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isConflict());
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isConflict());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-on",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isNoContent());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNoContent());
        mvc.perform(chatSendPost(table, memberSession, csrf, "text-allowed", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isCreated());
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-noop",
                "{\"permission\":\"text\",\"enabled\":true}")).andExpect(status().isNoContent());
        for (String permission : List.of("bets", "react", "gifs")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, permission + "-off",
                    "{\"permission\":\"" + permission + "\",\"enabled\":false}")).andExpect(status().isNoContent());
        }
        mvc.perform(chatSendPost(table, memberSession, csrf, "gif-blocked", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(reactionPut(message, memberSession, csrf, "react-blocked", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isForbidden());
        JsonNode hostTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode memberTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode observerTable = json.readTree(mvc.perform(get("/tables/" + table).cookie(observerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostTable.path("disabledPermissions").path(member.toString()).size()).isEqualTo(3);
        assertThat(memberTable.path("disabledPermissions").path(member.toString()).size()).isEqualTo(3);
        assertThat(observerTable.path("disabledPermissions").has(member.toString())).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.member.permission.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text, 'userId', ?::text)
                """, Integer.class, table.toString(), table.toString(), member.toString())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND kind = 'system'",
                Integer.class, table)).isZero();
        for (String permission : List.of("bets", "react", "gifs")) {
            mvc.perform(permissionPut(table, member, hostSession, csrf, permission + "-on",
                    "{\"permission\":\"" + permission + "\",\"enabled\":true}")).andExpect(status().isNoContent());
        }
        mvc.perform(chatSendPost(table, memberSession, csrf, "gif-allowed", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(reactionPut(message, memberSession, csrf, "react-allowed", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", observer);
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "frozen-target", disableText))
                .andExpect(status().isNoContent());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, observer);
        mvc.perform(permissionPut(table, observer, hostSession, csrf, "frozen-target", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("""
                UPDATE table_members SET role = 'HOST', can_bet = true, can_text = true,
                    can_react = true, can_send_gifs = true WHERE table_id = ? AND user_id = ?
                """, table, member);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(permissionPut(table, member, hostSession, csrf, "text-off", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(permissionPut(table, observer, loginAs("permhostpeer"), csrf, "archived", disableText))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        assertThat(tablePermissions.set(validated, table, member, TablePermissionService.Permission.TEXT, false, "text-off"))
                .isEqualTo(TablePermissionService.Outcome.UNAUTHORIZED);
    }

    @Test
    void permissionChangeSerializesWithMemberChatSend() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permracemember', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'P4R8QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, table, host, table, member);
        Jwt hostJwt = decoder.decode(loginAs("permracehost").getValue());
        Jwt memberJwt = decoder.decode(loginAs("permracemember").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var change = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tablePermissions.set(hostJwt, table, member, TablePermissionService.Permission.TEXT, false, "race");
            });
            var send = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(memberJwt, table, "race-send", new ChatSendService.Submission("text", "Hello"));
            });
            start.countDown();
            assertThat(change.get(10, TimeUnit.SECONDS)).isEqualTo(TablePermissionService.Outcome.UPDATED);
            ChatSendService.Outcome result = send.get(10, TimeUnit.SECONDS).outcome();
            assertThat(result).isIn(ChatSendService.Outcome.CREATED, ChatSendService.Outcome.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(result == ChatSendService.Outcome.CREATED ? 1 : 0);
            assertThat(jdbc.queryForObject("SELECT can_text FROM table_members WHERE table_id = ? AND user_id = ?",
                    Boolean.class, table, member)).isFalse();
            assertThat(chatSend.send(memberJwt, table, "after-race", new ChatSendService.Submission("text", "Later"))
                    .outcome()).isEqualTo(ChatSendService.Outcome.FORBIDDEN);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void permissionExpiryRollsBackClaimAndWrite() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'permexpiryhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'permexpirymem', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'P4X9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        Jwt validated = decoder.decode(loginAs("permexpiryhost").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tablePermissions.set(
                    expiring, table, member, TablePermissionService.Permission.TEXT, false, "expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT can_text FROM table_members WHERE table_id = ? AND user_id = ?",
                    Boolean.class, table, member)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableProfileChangesRespectAccessRetriesAndInvitationViews() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID invitee = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profile_host', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_member', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_invitee', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profile_outside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, invitee, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Before', '🏈', 'Y6R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", invitee, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invitation, table, host, invitee);
        Cookie hostSession = loginAs("profile_host");
        Cookie memberSession = loginAs("profile_member");
        Cookie inviteeSession = loginAs("profile_invitee");
        Cookie outsideSession = loginAs("profile_outside");
        Csrf csrf = csrf();
        String both = "{\"name\":\"  New title  \",\"emoji\":\"🎲\"}";
        mvc.perform(patch("/tables/" + table).cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table).cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"name\":null}", "{\"emoji\":false}", "{\"name\":\"   \"}",
                "{\"name\":\"x\\u0000y\"}", "{\"name\":\"\\uD800\"}",
                "{\"name\":\"" + "🙂".repeat(41) + "\"}", "{\"emoji\":\"not emoji\"}",
                "{\"name\":\"valid\",\"other\":true}")) {
            mvc.perform(profilePatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(profilePatch(table, memberSession, csrf, "member", both)).andExpect(status().isForbidden());
        mvc.perform(profilePatch(table, outsideSession, csrf, "outside", both)).andExpect(status().isNotFound());
        mvc.perform(profilePatch(UUID.randomUUID(), hostSession, csrf, "missing", both)).andExpect(status().isNotFound());
        MvcResult first = mvc.perform(profilePatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(first.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(view)).isEmpty();
        assertThat(view.path("name").asText()).isEqualTo("New title");
        assertThat(view.path("emoji").asText()).isEqualTo("🎲");
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT text FROM chat_messages WHERE table_id = ?", String.class, table))
                .isEqualTo("Table details changed");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.profile.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, table.toString(), table.toString())).isEqualTo(1);
        MvcResult inbox = mvc.perform(get("/me/invites").cookie(inviteeSession)).andExpect(status().isOk()).andReturn();
        JsonNode inviteView = json.readTree(inbox.getResponse().getContentAsString()).get(0);
        assertThat(inviteView.path("tableName").asText()).isEqualTo("New title");
        assertThat(inviteView.path("tableEmoji").asText()).isEqualTo("🎲");
        mvc.perform(profilePatch(table, hostSession, csrf, "first", "{\"name\":\"New title\",\"emoji\":\"🎲\"}"))
                .andExpect(status().isOk());
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", "{\"hostsOnlyBets\":true}"))
                .andExpect(status().isConflict());
        mvc.perform(profilePatch(table, hostSession, csrf, "first", "{\"name\":\"Different\"}"))
                .andExpect(status().isConflict());
        mvc.perform(profilePatch(table, hostSession, csrf, "second", "{\"name\":\"Later\"}"))
                .andExpect(status().isOk());
        MvcResult replay = mvc.perform(profilePatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("name").asText()).isEqualTo("Later");
        mvc.perform(profilePatch(table, hostSession, csrf, "noop", "{\"name\":\"  Later  \"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        mvc.perform(profilePatch(table, hostSession, csrf, "max", "{\"name\":\"" + "🙂".repeat(40) + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND text <> 'Table details changed'",
                Integer.class, table)).isZero();
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(profilePatch(table, hostSession, csrf, "first", both)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentTableProfilePartialUpdatesPreserveBothChanges() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'profileracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'profileraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', '🏈', 'Y6R7QB')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("profileracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("profileraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableProfile.update(firstJwt, table, new TableProfileService.Changes("New", null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableProfile.update(secondJwt, table, new TableProfileService.Changes(null, "🎲"), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(TableProfileService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(TableProfileService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT name = 'New' AND emoji = '🎲' FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableProfileExpiryRollsBackChangesAndEvents() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'profile_expire', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Before', '🏈', 'Y6R7QC')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("profile_expire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableProfile.update(expiring, table,
                    new TableProfileService.Changes("After", "🎲"), "expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT name = 'Before' AND emoji = '🏈' FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void quickPickControlsRespectAccessRetriesAndPreserveCopies() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID original = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'qpmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'qpoutsider', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'QP', 'T', 'Q8R7QA', false, false), (?, 'Other', 'T', 'Q8R7QB', true, true)
                """, table, other);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, original, table, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, source_bet_id, source_table_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now(), ?, ?)
                """, copy, other, host, original, table);
        Cookie hostSession = loginAs("qphost");
        Cookie memberSession = loginAs("qpmember");
        Cookie outsiderSession = loginAs("qpoutsider");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/quick-picks";
        for (String invalid : List.of("{}", "{\"enabled\":null}", "{\"enabled\":1}", "{\"enabled\":true,\"x\":1}")) {
            mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "bad", invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, memberSession, csrf, "member", "{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, outsiderSession, csrf, "outside", "{\"enabled\":true}"))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/share", hostSession, csrf, "share", null))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "body", "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "enable", "{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled AND quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("Q8R7QA");
        jdbc.update("""
                INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)
                """, table, other, other, table);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path + "/share", hostSession, csrf, "stop", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? AND source_table_id = ?
                """, Integer.class, table, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? AND source_table_id = ?
                """, Integer.class, other, table)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.quickpicks.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, other.toString(), other.toString())).isEqualTo(1);
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/share", hostSession, csrf, "share", null))
                .andExpect(status().isNoContent());
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                other, table);
        MvcResult first = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isOk()).andReturn();
        String code1 = json.readTree(first.getResponse().getContentAsString()).path("code").asText();
        assertThat(code1).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}").isNotEqualTo("Q8R7QA");
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ?",
                Integer.class, table)).isZero();
        MvcResult second = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate2", null))
                .andExpect(status().isOk()).andReturn();
        String code2 = json.readTree(second.getResponse().getContentAsString()).path("code").asText();
        assertThat(code2).isNotEqualTo(code1);
        MvcResult replay = mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("code").asText()).isEqualTo(code2);
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "rotate1", "{\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "disable", "{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT NOT quickpicks_enabled AND NOT quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id = ? OR source_table_id = ?
                """, Integer.class, table, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_bet_id FROM bets WHERE id = ?", UUID.class, copy)).isEqualTo(original);
        int notices = jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table);
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "noop", "{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(quickPickRequest(HttpMethod.PUT, path, hostSession, csrf, "enable", "{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(notices);
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled FROM tables WHERE id = ?", Boolean.class, table))
                .isFalse();
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(quickPickRequest(HttpMethod.POST, path + "/rotate", hostSession, csrf, "rotate1", null))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentQuickPickDisableDisconnectsReciprocalSubscriptions() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID firstTable = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qpraceone', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'qpracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'First', 'T', 'Q8R8QA', true, true), (?, 'Second', 'T', 'Q8R8QB', true, true)
                """, firstTable, secondTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                firstTable, first, secondTable, second);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)",
                firstTable, secondTable, secondTable, firstTable);
        Jwt firstJwt = decoder.decode(loginAs("qpraceone").getValue());
        Jwt secondJwt = decoder.decode(loginAs("qpracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableQuickPicks.change(firstJwt, firstTable, TableQuickPickService.Action.DISABLE, "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableQuickPicks.change(secondJwt, secondTable, TableQuickPickService.Action.DISABLE, "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableQuickPickService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableQuickPickService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM quickpick_subscriptions WHERE subscriber_table_id IN (?, ?)
                        OR source_table_id IN (?, ?)
                    """, Integer.class, firstTable, secondTable, firstTable, secondTable)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void quickPickCodeCollisionDoesNotAbortTheTransaction() {
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code)
                VALUES (?, 'QP', 'T', 'Q8R6QA'), (?, 'Other', 'T', 'Q8R6QB')
                """, table, other);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(tableQuickPicks.tryRotate(table, "Q8R6QB")).isFalse();
            assertThat(tableQuickPicks.tryRotate(table, "Q8R6QC")).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT quickpick_code FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("Q8R6QC");
    }

    @Test
    void quickPickAndMemberRemovalRollbackIfSessionExpiresWhileBlocked() throws Exception {
        UUID host = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID subscriber = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'qpexpiry', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'rmexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, target, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'QP', 'T', 'Q8R9QA', true, true), (?, 'Subscriber', 'T', 'Q8R9QB', true, true)
                """, table, subscriber);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, target);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                subscriber, table);
        Jwt validated = decoder.decode(loginAs("qpexpiry").getValue());
        verifyMutationExpiry(validated,
                jwt -> tableQuickPicks.change(jwt, table, TableQuickPickService.Action.DISABLE, "qp-expiry"));
        assertThat(jdbc.queryForObject("SELECT quickpicks_enabled AND quickpick_sharing FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ?",
                Integer.class, table)).isEqualTo(1);
        verifyMutationExpiry(validated, jwt -> tableMemberRemovals.remove(jwt, table, target, "rm-expiry"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id IN (?, ?)",
                Integer.class, table.toString(), subscriber.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
    }

    @Test
    void tableSettingsRespectAccessRetriesAndExistingBets() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'settingshost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingsmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingsoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Settings', 'T', 'Y5R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, member);
        Cookie hostSession = loginAs("settingshost");
        Cookie memberSession = loginAs("settingsmember");
        Cookie outsideSession = loginAs("settingsoutside");
        Csrf csrf = csrf();
        String both = "{\"hostsOnlyBets\":true,\"u2pickEnabled\":false}";
        mvc.perform(patch("/tables/" + table + "/settings").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table + "/settings").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(both)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"hostsOnlyBets\":null}", "{\"hostsOnlyBets\":\"true\"}",
                "{\"u2pickEnabled\":0}", "{\"unknown\":true}", "{\"hostsOnlyBets\":true,\"unknown\":false}")) {
            mvc.perform(settingsPatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(settingsPatch(table, memberSession, csrf, "member", both)).andExpect(status().isForbidden());
        mvc.perform(settingsPatch(table, outsideSession, csrf, "outsider", both)).andExpect(status().isNotFound());
        mvc.perform(settingsPatch(UUID.randomUUID(), hostSession, csrf, "missing", both)).andExpect(status().isNotFound());
        MvcResult first = mvc.perform(settingsPatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        JsonNode view = json.readTree(first.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(view)).isEmpty();
        assertThat(view.path("hostsOnlyBets").asBoolean()).isTrue();
        assertThat(view.path("u2pickEnabled").asBoolean()).isFalse();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.settings.changed.v1'
                """, Integer.class, table.toString())).isEqualTo(1);
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", both)).andExpect(status().isOk());
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(settingsPatch(table, hostSession, csrf, "second", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isOk());
        MvcResult replay = mvc.perform(settingsPatch(table, hostSession, csrf, "first", both))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("hostsOnlyBets").asBoolean()).isFalse();
        mvc.perform(settingsPatch(table, hostSession, csrf, "noop", "{\"hostsOnlyBets\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(3);
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, member);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(settingsPatch(table, hostSession, csrf, "first", both)).andExpect(status().isForbidden());
    }

    @Test
    void concurrentTableSettingsUpdatesPreserveBothChanges() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'setraceone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'setracetwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Y5R7QB')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("setraceone").getValue());
        Jwt secondJwt = decoder.decode(loginAs("setracetwo").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableSettings.update(firstJwt, table, new TableSettingsService.Changes(true, null), "one");
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableSettings.update(secondJwt, table, new TableSettingsService.Changes(null, false), "two");
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(TableSettingsService.Outcome.UPDATED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(TableSettingsService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT hosts_only_bets AND NOT u2pick_enabled FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableSettingsExpiryRollsBackChangesAndEvents() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'setexpiryhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'Y5R7QC')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("setexpiryhost").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableSettings.update(expiring, table,
                    new TableSettingsService.Changes(true, false), "expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT NOT hosts_only_bets AND u2pick_enabled FROM tables WHERE id = ?",
                    Boolean.class, table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void remainderChangesRespectActiveBetsAuthorizationAndRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'remaindhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'remaindmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'remaindoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Remainder', 'T', 'Z9R7QA')", table);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Other', 'T', 'Y4R7QB')", otherTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Other?', '["Yes","No"]'::jsonb, 100, false, now())
                """, UUID.randomUUID(), otherTable, host);
        Cookie hostSession = loginAs("remaindhost");
        Cookie memberSession = loginAs("remaindmember");
        Cookie outsideSession = loginAs("remaindoutside");
        Jwt validated = decoder.decode(hostSession.getValue());
        Csrf csrf = csrf();
        String proposer = "{\"rule\":\"proposer\"}";
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON).content(proposer))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(hostSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(proposer)).andExpect(status().isForbidden());
        mvc.perform(patch("/tables/" + table + "/remainder").cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(proposer)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"rule\":null}", "{\"rule\":true}", "{\"rule\":\"bad\"}",
                "{\"rule\":\"host\",\"extra\":1}")) {
            mvc.perform(remainderPatch(table, hostSession, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(remainderPatch(table, memberSession, csrf, "member", proposer)).andExpect(status().isForbidden());
        mvc.perform(remainderPatch(table, outsideSession, csrf, "outside", proposer)).andExpect(status().isNotFound());
        mvc.perform(remainderPatch(UUID.randomUUID(), hostSession, csrf, "missing", proposer))
                .andExpect(status().isNotFound());
        MvcResult first = mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(remainderPatch(table, hostSession, csrf, "second", "{\"rule\":\"random\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", "{\"rule\":\"random\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                .isEqualTo("random");
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "noop", "{\"rule\":\"random\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "blocked", "{\"rule\":\"earliest\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'proposer' WHERE id = ?
                """, bet);
        mvc.perform(remainderPatch(table, hostSession, csrf, "blocked", "{\"rule\":\"earliest\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(remainderPatch(table, hostSession, csrf, "host-rule", "{\"rule\":\"host\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT remainder_rule FROM bets WHERE id = ?", String.class, bet))
                .isEqualTo("proposer");
        JsonNode window = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(window.path("items").size()).isEqualTo(4);
        assertThat(window.path("items").get(2).path("kind").asText()).isEqualTo("system");
        assertThat(window.path("items").get(2).path("text").asText()).isEqualTo("Leftover cents set to: Earliest pick");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ?
                  AND event_type IN ('table.remainder.changed.v1', 'chat.message.created.v1')
                """, Integer.class, table.toString())).isEqualTo(8);
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(remainderPatch(table, hostSession, csrf, "first", proposer)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(remainderPatch(table, memberSession, csrf, "archived", proposer)).andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        assertThat(tableRemainder.set(validated, table, TableRemainderService.Rule.PROPOSER, "first"))
                .isEqualTo(TableRemainderService.Outcome.UNAUTHORIZED);
    }

    @Test
    void remainderChangeWaitsForActiveBetAndRollsBackOnExpiry() throws Exception {
        UUID host = UUID.randomUUID();
        UUID proposer = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'remaindrace', 1, ?, 'PLAYER', 'ACTIVE'),
                       (?, 'remaindpropose', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, proposer, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Z9R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("remaindrace").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var outboxLocked = new java.util.concurrent.CountDownLatch(1);
        var outboxRelease = new java.util.concurrent.CountDownLatch(1);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("""
                        INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                          wager_cents, is_4fun, locks_at)
                        VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                        """, bet, table, proposer);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var change = executor.submit(() -> tableRemainder.set(validated, table, TableRemainderService.Rule.RANDOM, "race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT remainder_rule FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> change.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(TableRemainderService.ActiveBet.class);
            assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                    .isEqualTo("host");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                    .isZero();
            jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'random' WHERE id = ?", bet);
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.execute("LOCK TABLE outbox IN EXCLUSIVE MODE");
                outboxLocked.countDown();
                try {
                    if (!outboxRelease.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(outboxLocked.await(10, TimeUnit.SECONDS)).isTrue();
            java.time.Instant expiry = java.time.Instant.now().plusSeconds(3);
            Jwt expiring = Jwt.withTokenValue("expiring").header("alg", "HS256")
                    .subject(host.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableRemainder.set(expiring, table, TableRemainderService.Rule.RANDOM, "expiry"));
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            outboxRelease.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT remainder_rule FROM tables WHERE id = ?", String.class, table))
                    .isEqualTo("host");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            outboxRelease.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder permissionPut(
            UUID table, UUID target, Cookie session, Csrf csrf, String key, String body) {
        return put("/tables/" + table + "/members/" + target + "/permissions").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder profilePatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder settingsPatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table + "/settings").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder remainderPatch(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return patch("/tables/" + table + "/remainder").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
