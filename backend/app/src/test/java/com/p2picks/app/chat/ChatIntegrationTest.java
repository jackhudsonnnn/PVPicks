package com.p2picks.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
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

class ChatIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private ChatReadService chatRead;

    @Autowired
    private ChatSendService chatSend;

    @Autowired
    private ChatWindowService chatWindows;

    @Autowired
    private ReactionService reactions;

    @Test
    void reactionsValidateReplaceRemoveAndRecheckAccessOnRetry() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'reactactor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reactauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'reactoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, author, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Reactions', 'T', 'R2X4QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, actor, table, author);
        UUID bet = insertDebtBet(table, actor, author, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Hello'), (?, ?, 'system', NULL, 'System')
                """, text, table, author, system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        Cookie session = loginAs("reactactor");
        Cookie authorSession = loginAs("reactauthor");
        Cookie outsideSession = loginAs("reactoutside");
        Csrf csrf = csrf();
        mvc.perform(put("/messages/" + text + "/reaction").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\":\"😀\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/messages/" + text + "/reaction").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"emoji\":\"😀\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "bad-delete").content("unexpected"))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"emoji\":\"\"}", "{\"emoji\":\"hello\"}",
                "{\"emoji\":\"😀😀\"}", "{\"emoji\":\"😀 \"}", "{\"emoji\":1}", "{\"emoji\":\"😀\",\"x\":1}")) {
            mvc.perform(reactionPut(text, session, csrf, "bad-reaction", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(reactionPut(text, outsideSession, csrf, "outside", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(system, session, csrf, "system", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        MvcResult result = mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(reactionPut(text, authorSession, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "replace", "{\"emoji\":\"👨‍👩‍👧‍👦\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isConflict());
        mvc.perform(reactionPut(gif, session, csrf, "first", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isConflict());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "first"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT emoji FROM reactions WHERE message_id = ? AND user_id = ?",
                String.class, text, actor)).isEqualTo("👨‍👩‍👧‍👦");
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove-again"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionPut(text, session, csrf, "same-value", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(reactionRequest(HttpMethod.DELETE, text, session, csrf, "remove"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reactions WHERE message_id = ?", Integer.class, text))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'chat.reaction.changed.v1'
                    AND payload = jsonb_build_object('tableId', ?::text)
                """, Integer.class, table.toString(), table.toString())).isEqualTo(5);
        jdbc.update("UPDATE table_members SET can_react = false WHERE table_id = ? AND user_id = ?", table, actor);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE table_members SET can_react = true WHERE table_id = ? AND user_id = ?", table, actor);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", actor, author);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(gif, session, csrf, "gif", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(reactionPut(betMessage, session, csrf, "bet", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", actor, author);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", author, actor);
        mvc.perform(reactionPut(gif, session, csrf, "gif", "{\"emoji\":\"😀\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, actor);
        mvc.perform(reactionPut(text, session, csrf, "later", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(reactionPut(text, authorSession, csrf, "first", "{\"emoji\":\"👍🏽\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reactionExpiryRollsBackClaimAndWrite() throws Exception {
        UUID user = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'reactexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'R2X5QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, user);
        jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀')", message, user);
        Jwt validated = decoder.decode(loginAs("reactexpiry").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT message_id FROM reactions WHERE message_id = ? AND user_id = ? FOR UPDATE",
                        UUID.class, message, user);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> reactions.change(expiring, message, "👍", "reaction-expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO reactions (message_id, user_id, emoji)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT emoji FROM reactions WHERE message_id = ? AND user_id = ?",
                    String.class, message, user)).isEqualTo("😀");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void chatReadStateFiltersAndAdvances() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID own = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'readchatuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readchatauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readchatoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Unread', 'T', 'C4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, user, table, author);
        UUID bet = insertDebtBet(table, user, author, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, author_id, text)
                VALUES (?, ?, 'text', ?, 'Own'), (?, ?, 'text', ?, 'First')
                """, own, table, user, first, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'System')", system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Last')",
                last, table, author);
        Cookie session = loginAs("readchatuser");
        Cookie outside = loginAs("readchatoutside");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(get("/tables/" + table + "/last-read")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tables/" + table + "/last-read").cookie(outside)).andExpect(status().isNotFound());
        mvc.perform(put("/tables/" + table + "/last-read").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isForbidden());
        assertUnread(table, session, first, 5);
        for (String invalid : List.of("{}", "{\"messageId\":\"bad\"}", "{\"messageId\":\"1-1-1-1-1\"}",
                "{\"messageId\":null}", "{\"messageId\":\"" + first + "\",\"extra\":true}")) {
            mvc.perform(chatReadPut(table, session, csrf, "bad", invalid)).andExpect(status().isBadRequest());
        }
        MvcResult saved = mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertUnread(table, session, gif, 4);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + gif + "\"}"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        assertUnread(table, session, system, 2);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "blocked", "{\"messageId\":\"" + gif + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "system", "{\"messageId\":\"" + system + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, betMessage, 1);
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", user, author);
        assertUnread(table, session, betMessage, 2);
        mvc.perform(chatReadPut(table, session, csrf, "first", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(chatReadPut(table, session, csrf, "older", "{\"messageId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, betMessage, 2);
        mvc.perform(chatReadPut(table, session, csrf, "last", "{\"messageId\":\"" + last + "\"}"))
                .andExpect(status().isNoContent());
        assertUnread(table, session, null, 0);
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", last);
        UUID after = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'After')",
                after, table, author);
        UUID deletedAuthor = UUID.randomUUID();
        UUID deletedText = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, role, status, deleted_at) VALUES (?, 'PLAYER', 'DELETED', now())
                """, deletedAuthor);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hidden')",
                deletedText, table, deletedAuthor);
        assertUnread(table, session, after, 1);
        mvc.perform(chatReadPut(table, session, csrf, "deleted", "{\"messageId\":\"" + deletedText + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "last", "{\"messageId\":\"" + last + "\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(get("/tables/" + table + "/last-read").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(chatReadPut(table, session, csrf, "after", "{\"messageId\":\"" + after + "\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/tables/" + table + "/last-read").cookie(loginAs("readchatauthor")))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatRead.read(validated, table).authenticated()).isFalse();
        assertThat(chatRead.advance(validated, table, after, "after")).isEqualTo(ChatReadService.Outcome.UNAUTHORIZED);
    }

    @Test
    void chatReadExpiryRollsBackPositionAndRetryClaim() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'readexpire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'readexpirepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'C4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Previous')",
                previous, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hi')",
                message, table, author);
        Long previousOrder = jdbc.queryForObject("SELECT message_order FROM chat_messages WHERE id = ?", Long.class, previous);
        jdbc.update("INSERT INTO chat_read_positions (table_id, user_id, last_read_order) VALUES (?, ?, ?)",
                table, user, previousOrder);
        Jwt validated = decoder.decode(loginAs("readexpire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT user_id FROM chat_read_positions WHERE table_id = ? AND user_id = ? FOR UPDATE",
                        UUID.class, table, user);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> chatRead.advance(expiring, table, message, "read-expiry"));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO chat_read_positions (table_id, user_id, last_read_order)%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT last_read_order FROM chat_read_positions WHERE user_id = ?",
                    Long.class, user)).isEqualTo(previousOrder);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void chatWindowsPageInStableOrderAndRecheckAccess() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'windowuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'windowauthor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'windowoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash, outsider, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Window', 'T', 'W2R7QA'), (?, 'Other', 'T', 'W2R8QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, table, user, table, author, otherTable, user);
        List<UUID> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            UUID id = UUID.randomUUID();
            messages.add(id);
            jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                    id, table, author, "Message " + i);
        }
        Cookie session = loginAs("windowuser");
        Cookie otherSession = loginAs("windowauthor");
        Cookie outside = loginAs("windowoutside");
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(get("/chat/" + table + "/messages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/chat/" + table + "/messages").cookie(outside)).andExpect(status().isNotFound());
        JsonNode latest = chatWindow(table, session, null, null);
        assertWindowItems(latest, messages.subList(50, 80));
        String tail = latest.path("nextCursor").asText();
        assertThat(tail).isNotBlank();
        assertThat(chatWindow(table, session, "after", tail).path("items").isEmpty()).isTrue();
        String previous = latest.path("previousCursor").asText();
        assertThat(previous).isNotBlank().doesNotContain(messages.get(50).toString());
        mvc.perform(get("/chat/" + table + "/messages").cookie(otherSession).param("before", previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + otherTable + "/messages").cookie(session).param("before", previous))
                .andExpect(status().isBadRequest());
        String tampered = (previous.charAt(0) == 'A' ? "B" : "A") + previous.substring(1);
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", tampered))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous).param("after", previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous, previous))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("around", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("around", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        JsonNode around = chatWindow(table, session, "around", messages.get(40).toString());
        assertWindowItems(around, messages.subList(35, 65));
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", messages.get(50));
        JsonNode middle = chatWindow(table, session, "before", previous);
        assertWindowItems(middle, messages.subList(25, 50));
        JsonNode earliest = chatWindow(table, session, "before", middle.path("previousCursor").asText());
        assertWindowItems(earliest, messages.subList(0, 25));
        assertThat(earliest.path("previousCursor").isNull()).isTrue();
        JsonNode forward = chatWindow(table, session, "after", earliest.path("nextCursor").asText());
        assertWindowItems(forward, messages.subList(25, 50));
        String afterMiddle = middle.path("nextCursor").asText();
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", messages.get(49));
        JsonNode forwardAfterDeletedAnchor = chatWindow(table, session, "after", afterMiddle);
        assertWindowItems(forwardAfterDeletedAnchor, messages.subList(51, 76));
        List<UUID> arrivals = new java.util.ArrayList<>();
        for (int i = 0; i < 31; i++) {
            UUID id = UUID.randomUUID();
            arrivals.add(id);
            jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, ?)",
                    id, table, author, "Arrival " + i);
        }
        JsonNode newPage = chatWindow(table, session, "after", tail);
        assertWindowItems(newPage, arrivals.subList(0, 25));
        JsonNode remaining = chatWindow(table, session, "after", newPage.path("nextCursor").asText());
        assertWindowItems(remaining, arrivals.subList(25, 31));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        JsonNode hidden = chatWindow(table, session, null, null);
        assertThat(hidden.path("items").isEmpty()).isTrue();
        assertThat(hidden.path("previousCursor").isNull()).isTrue();
        assertThat(hidden.path("nextCursor").isNull()).isTrue();
        mvc.perform(get("/chat/" + table + "/messages").cookie(session)
                .param("around", messages.get(40).toString())).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", user, author);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(get("/chat/" + table + "/messages").cookie(session).param("before", previous))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(otherSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatWindows.read(validated, table,
                new ChatWindowService.Query(ChatWindowService.Direction.LATEST, null, null)).authenticated()).isFalse();
    }

    @Test
    void chatWindowsProjectOnlyAuthorizedMessageFields() throws Exception {
        UUID user = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        UUID gif = UUID.randomUUID();
        UUID betMessage = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID settlementMessage = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'projectionuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'projectionpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, author, hash);
        jdbc.update("INSERT INTO users (id, role, status, deleted_at) VALUES (?, 'PLAYER', 'DELETED', now())", deleted);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Projection', 'T', 'W2R9QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, user, table, author);
        UUID bet = insertDebtBet(table, user, author, "ACTIVE", false, false, false, 100, 0);
        UUID settlement = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlement, table, author);
        jdbc.update("""
                INSERT INTO settlement_lines (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 123, 'sent')
                """, UUID.randomUUID(), settlement, table, author, user, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hello')",
                text, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, gif_id) VALUES (?, ?, 'gif', ?, 'gif')",
                gif, table, author);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, bet_id) VALUES (?, ?, 'bet', ?, ?)",
                betMessage, table, author, bet);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, text) VALUES (?, ?, 'system', 'System')", system, table);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                settlementMessage, table, settlement);
        jdbc.update("""
                INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀'), (?, ?, '👍'), (?, ?, '🔥')
                """, betMessage, user, betMessage, author, betMessage, deleted);
        Cookie session = loginAs("projectionuser");
        JsonNode window = chatWindow(table, session, null, null);
        assertWindowItems(window, List.of(text, gif, betMessage, system, settlementMessage));
        for (JsonNode message : window.path("items")) {
            assertThat(OpenApiSchemas.load("ChatMessage").validate(message)).isEmpty();
        }
        JsonNode projectedBet = window.path("items").get(2);
        assertThat(projectedBet.path("betId").asText()).isEqualTo(bet.toString());
        assertThat(projectedBet.has("picks")).isFalse();
        assertThat(projectedBet.path("reactions").size()).isEqualTo(2);
        JsonNode summary = window.path("items").get(4).path("settlementSummary");
        assertThat(summary.path("hostId").asText()).isEqualTo(author.toString());
        assertThat(summary.path("lines").get(0).path("amountCents").asLong()).isEqualTo(123);
        assertThat(summary.path("lines").get(0).has("status")).isFalse();
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", user, author);
        JsonNode filtered = chatWindow(table, session, null, null);
        assertWindowItems(filtered, List.of(betMessage, system, settlementMessage));
        assertThat(filtered.path("items").get(0).path("reactions").size()).isEqualTo(2);
    }

    @Test
    void chatSendValidatesPermissionsAndReplaysOriginalResponse() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'sendchatuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'sendchatother', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Chat', 'T', 'S4R7QA'), (?, 'Other', 'T', 'S4R8QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'HOST')
                """, table, user, table, other, otherTable, user);
        Cookie session = loginAs("sendchatuser");
        Cookie otherSession = loginAs("sendchatother");
        Csrf csrf = csrf();
        Jwt validated = decoder.decode(session.getValue());
        mvc.perform(post("/tables/" + table + "/messages").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"text\",\"text\":\"Hi\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/tables/" + table + "/messages").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/tables/" + table + "/messages").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", "{\"kind\":\"system\",\"text\":\"Hi\"}",
                "{\"kind\":\"text\",\"text\":\"  \\t \"}", "{\"kind\":\"text\",\"text\":\"\\u0000\"}",
                "{\"kind\":\"text\",\"text\":\"\\uD800\"}", "{\"kind\":\"text\",\"text\":4}",
                "{\"kind\":\"text\",\"text\":\"Hi\",\"extra\":true}",
                "{\"kind\":\"gif\",\"gifId\":\"g25\"}", "{\"kind\":\"gif\",\"gifId\":\" g1 \"}",
                "{\"kind\":\"gif\",\"gifId\":\"g01\"}")) {
            mvc.perform(chatSendPost(table, session, csrf, "bad-send", invalid)).andExpect(status().isBadRequest());
        }
        String tooLong = json.writeValueAsString(json.createObjectNode().put("kind", "text").put("text", "😀".repeat(2001)));
        mvc.perform(chatSendPost(table, session, csrf, "bad-send", tooLong)).andExpect(status().isBadRequest());
        String limit = json.writeValueAsString(json.createObjectNode().put("kind", "text").put("text", "😀".repeat(2000)));
        MvcResult maxLength = mvc.perform(chatSendPost(otherTable, session, csrf, "limit", limit))
                .andExpect(status().isCreated()).andReturn();
        String savedText = json.readTree(maxLength.getResponse().getContentAsString()).path("text").asText();
        assertThat(savedText).isEqualTo("😀".repeat(2000));
        mvc.perform(chatSendPost(otherTable, otherSession, csrf, "outside",
                "{\"kind\":\"text\",\"text\":\"Hi\"}")).andExpect(status().isNotFound());
        MvcResult created = mvc.perform(chatSendPost(table, session, csrf, "first",
                "{\"kind\":\"text\",\"text\":\"  Hello \\uFEFF\"}")).andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode response = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("ChatMessage").validate(response)).isEmpty();
        assertThat(response.path("text").asText()).isEqualTo("Hello");
        UUID message = UUID.fromString(response.path("id").asText());
        jdbc.update("INSERT INTO reactions (message_id, user_id, emoji) VALUES (?, ?, '😀')", message, other);
        MvcResult replay = mvc.perform(chatSendPost(table, session, csrf, "first",
                "{\"kind\":\"text\",\"text\":\"Hello\"}")).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString())).isEqualTo(response);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Changed\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(otherTable, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(chatSendPost(table, otherSession, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g24\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'chat.message.created.v1'
                    AND payload = jsonb_build_object('tableId', ?::text, 'messageId', ?::text, 'kind', 'text')
                """, Integer.class, table.toString(), table.toString(), message.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM http_idempotency WHERE user_id = ? AND response_resource_id = ?
                """, Integer.class, user, message)).isEqualTo(1);
        assertThat(chatWindow(table, session, null, null).path("items").size()).isEqualTo(2);
        jdbc.update("UPDATE table_members SET can_text = false WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(chatSendPost(table, session, csrf, "gif", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE table_members SET can_text = true, can_send_gifs = false WHERE table_id = ? AND user_id = ?",
                table, user);
        mvc.perform(chatSendPost(table, session, csrf, "gif", "{\"kind\":\"gif\",\"gifId\":\"g1\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE chat_messages SET text = 'Redacted' WHERE id = ?", message);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("DELETE FROM chat_messages WHERE id = ?", message);
        mvc.perform(chatSendPost(table, session, csrf, "first", "{\"kind\":\"text\",\"text\":\"Hello\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE response_resource_id = ?",
                Integer.class, message)).isEqualTo(1);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, user);
        mvc.perform(chatSendPost(table, session, csrf, "new", "{\"kind\":\"text\",\"text\":\"Hi\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(chatSendPost(table, otherSession, csrf, "first", "{\"kind\":\"gif\",\"gifId\":\"g24\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(chatSend.send(validated, table, "first", new ChatSendService.Submission("text", "Hello")).outcome())
                .isEqualTo(ChatSendService.Outcome.UNAUTHORIZED);
    }

    @Test
    void chatSendSerializesDuplicateRetriesAndRollsBackOnExpiry() throws Exception {
        UUID user = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'sendchatrace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'S4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        Jwt validated = decoder.decode(loginAs("sendchatrace").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(validated, table, "concurrent", new ChatSendService.Submission("text", "Race"));
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return chatSend.send(validated, table, "concurrent", new ChatSendService.Submission("text", "Race"));
            });
            start.countDown();
            ChatSendService.Result one = first.get(10, TimeUnit.SECONDS);
            ChatSendService.Result two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one.outcome(), two.outcome()))
                    .containsExactlyInAnyOrder(ChatSendService.Outcome.CREATED, ChatSendService.Outcome.REPLAYED);
            assertThat(one.message()).isEqualTo(two.message());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
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
                    .subject(user.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var send = executor.submit(() -> chatSend.send(expiring, table, "expiry",
                    new ChatSendService.Submission("text", "Rollback")));
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
            assertThatThrownBy(() -> send.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertUnread(UUID table, Cookie session, UUID first, long count) throws Exception {
        MvcResult result = mvc.perform(get("/tables/" + table + "/last-read").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode state = json.readTree(result.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("ChatUnreadState").validate(state)).isEmpty();
        assertThat(state.path("unreadCount").asLong()).isEqualTo(count);
        if (first == null) assertThat(state.path("firstUnreadMessageId").isNull()).isTrue();
        else assertThat(state.path("firstUnreadMessageId").asText()).isEqualTo(first.toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chatReadPut(
            UUID table, Cookie session, Csrf csrf, String key, String body) {
        return put("/tables/" + table + "/last-read").cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertWindowItems(JsonNode window, List<UUID> expected) {
        List<String> actual = new java.util.ArrayList<>();
        window.path("items").forEach(message -> actual.add(message.path("id").asText()));
        assertThat(actual).containsExactlyElementsOf(expected.stream().map(UUID::toString).toList());
    }
}
