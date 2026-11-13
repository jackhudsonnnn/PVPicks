package com.p2picks.app.tables;

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
import com.p2picks.app.social.InvitationResponseService;
import com.p2picks.app.social.SocialMutationService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class TableMembershipIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private InvitationResponseService invitationResponses;

    @Autowired
    private SocialMutationService socialMutations;

    @Autowired
    private TableArchiveService tableArchives;

    @Autowired
    private TableCreationService tableCreation;

    @Autowired
    private TableHostService tableHosts;

    @Autowired
    private TableReadController tableReads;

    @Test
    void tableReadsEnforceMembershipAndRedactHostOnlyData() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID limited = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'tablehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tablemember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tablelimited', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'tableadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, host, hash, member, hash, limited, hash, admin, hash);
        UUID table = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'Visible', 'T', 'H2R5QA', true, true),
                       (?, 'Archived', 'T', 'J2R5QA', false, false),
                       (?, 'Other', 'T', 'K2R5QA', true, true)
                """, table, archived, source);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_bet, can_text, can_react)
                VALUES (?, ?, 'HOST', true, true, true),
                       (?, ?, 'MEMBER', true, false, false),
                       (?, ?, 'MEMBER', false, true, true),
                       (?, ?, 'HOST', true, true, true),
                       (?, ?, 'HOST', true, true, true)
                """, table, host, table, member, table, limited, archived, host, source, admin);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        jdbc.update("INSERT INTO table_archive_members (table_id, user_id) VALUES (?, ?)", archived, host);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?)",
                table, source);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, UUID.randomUUID(), source, admin, member);
        UUID bet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices,
                                  wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now() + interval '1 day')
                """, bet, table, host);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, limited);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, limited);

        mvc.perform(get("/tables")).andExpect(status().isUnauthorized());
        Cookie hostSession = loginAs("tablehost");
        Cookie memberSession = loginAs("tablemember");
        Cookie adminSession = loginAs("tableadmin");
        MvcResult hostList = mvc.perform(get("/tables").cookie(hostSession)).andExpect(status().isOk()).andReturn();
        assertThat(hostList.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode hostTables = json.readTree(hostList.getResponse().getContentAsString());
        assertThat(hostTables.size()).isEqualTo(1);
        JsonNode hostTable = hostTables.get(0);
        assertThat(OpenApiSchemas.load("Table").validate(hostTable)).isEmpty();
        assertThat(hostTable.path("id").asText()).isEqualTo(table.toString());
        assertThat(hostTable.path("quickPickCode").asText()).isEqualTo("H2R5QA");
        assertThat(hostTable.path("disabledPermissions").path(member.toString()).toString())
                .isEqualTo("[\"text\",\"react\"]");
        assertThat(hostTable.path("disabledPermissions").path(limited.toString()).toString())
                .isEqualTo("[\"bets\"]");
        assertThat(hostTable.path("subscribedTo").get(0).asText()).isEqualTo(source.toString());
        assertThat(hostTable.has("participants")).isFalse();
        assertThat(hostTable.has("picks")).isFalse();
        mvc.perform(get("/tables/" + archived).cookie(hostSession)).andExpect(status().isNotFound());

        MvcResult memberGet = mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(memberGet.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode memberTable = json.readTree(memberGet.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Table").validate(memberTable)).isEmpty();
        assertThat(memberTable.has("quickPickCode")).isFalse();
        assertThat(memberTable.path("disabledPermissions").path(member.toString()).toString())
                .isEqualTo("[\"text\",\"react\"]");
        assertThat(memberTable.path("disabledPermissions").has(limited.toString())).isFalse();
        mvc.perform(get("/tables/" + source).cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table).cookie(adminSession)).andExpect(status().isForbidden());
        mvc.perform(get("/tables/" + source).cookie(adminSession)).andExpect(status().isForbidden());
        mvc.perform(get("/tables/" + UUID.randomUUID()).cookie(memberSession)).andExpect(status().isNotFound());

        jdbc.update("""
                UPDATE table_members SET role = 'HOST', can_text = true, can_react = true
                WHERE table_id = ? AND user_id = ?
                """, table, member);
        JsonNode promoted = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(promoted.path("quickPickCode").asText()).isEqualTo("H2R5QA");
        assertThat(promoted.path("disabledPermissions").has(limited.toString())).isTrue();
        jdbc.update("""
                UPDATE table_members SET role = 'MEMBER', can_text = false
                WHERE table_id = ? AND user_id = ?
                """, table, member);
        JsonNode demoted = json.readTree(mvc.perform(get("/tables/" + table).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(demoted.has("quickPickCode")).isFalse();
        assertThat(demoted.path("disabledPermissions").has(limited.toString())).isFalse();
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, member);
        mvc.perform(get("/tables/" + table).cookie(memberSession)).andExpect(status().isNotFound());
        JsonNode memberTables = json.readTree(mvc.perform(get("/tables").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(memberTables.isEmpty()).isTrue();
        Jwt validatedBeforeRevocation = decoder.decode(memberSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", member);
        assertThat(tableReads.list(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/tables").cookie(memberSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void tableArchiveProtectsFinancialStateAndProvidesTimedFilteredReadOnlyChat() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID former = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'archhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archformer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, member, hash, former, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, quickpicks_enabled, quickpick_sharing)
                VALUES (?, 'Archived', 'T', 'A8R7QA', true, true), (?, 'Other', 'T', 'A8R7QB', true, true)
                """, table, other);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, member);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, former);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, former);
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, author_id, text) VALUES (?, ?, 'text', ?, 'Hello')",
                message, table, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, host, former);
        jdbc.update("INSERT INTO quickpick_subscriptions (subscriber_table_id, source_table_id) VALUES (?, ?), (?, ?)",
                table, other, other, table);
        UUID bet = insertDebtBet(table, member, host, "ACTIVE", false, false, false, 100, 0);
        UUID copy = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, source_bet_id, source_table_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Copy?', '["Yes","No"]'::jsonb, 100, false, now(), ?, ?)
                """, copy, other, host, bet, table);
        UUID receipt = insertDebtReceipt(table, host, member, host, 100, "unpaid");
        Cookie hostSession = loginAs("archhost");
        Cookie memberSession = loginAs("archmember");
        Cookie formerSession = loginAs("archformer");
        Csrf csrf = csrf();
        String path = "/tables/" + table;
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, memberSession, csrf, "member", null))
                .andExpect(status().isForbidden());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, formerSession, csrf, "former", null))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'earliest' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", bet);
        MvcResult success = mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(success.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(get(path).cookie(hostSession)).andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "delete", null))
                .andExpect(status().isNoContent());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "new", null))
                .andExpect(status().isNotFound());
        JsonNode archiveList = json.readTree(mvc.perform(get("/tables/archives").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(archiveList.size()).isEqualTo(1);
        assertThat(OpenApiSchemas.load("TableArchive").validate(archiveList.get(0))).isEmpty();
        assertThat(archiveList.get(0).path("id").asText()).isEqualTo(table.toString());
        assertThat(archiveList.get(0).path("name").asText()).isEqualTo("Archived");
        var archivedAt = java.time.Instant.parse(archiveList.get(0).path("archivedAt").asText());
        var expiresAt = java.time.Instant.parse(archiveList.get(0).path("expiresAt").asText());
        assertThat(java.time.Duration.between(archivedAt, expiresAt)).isEqualTo(java.time.Duration.ofDays(90));
        assertThat(json.readTree(mvc.perform(get("/tables/archives").cookie(formerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        assertThat(chatWindow(table, memberSession, null, null).path("items").size()).isEqualTo(1);
        mvc.perform(chatSendPost(table, memberSession, csrf, "archive-chat", "{\"kind\":\"text\",\"text\":\"No\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.PUT, "/messages/" + message + "/reaction", memberSession, csrf,
                "archive-reaction", "{\"emoji\":\"👍\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/chat/" + table + "/messages").cookie(formerSession)).andExpect(status().isNotFound());
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", member, host);
        assertThat(chatWindow(table, memberSession, null, null).path("items").isEmpty()).isTrue();
        mvc.perform(quickPickRequest(HttpMethod.PUT, path + "/quick-picks", hostSession, csrf, "enable",
                "{\"enabled\":true}")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickpick_subscriptions WHERE source_table_id = ? OR subscriber_table_id = ?",
                Integer.class, table, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_lines WHERE id = ? AND status = 'unpaid'",
                Integer.class, receipt)).isEqualTo(1);
        assertThat(json.readTree(mvc.perform(get("/me/debt").cookie(memberSession)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("debtCents").asLong()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE id = ?", Integer.class, bet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_bet_id FROM bets WHERE id = ?", UUID.class, copy)).isEqualTo(bet);
        jdbc.update("UPDATE tables SET archived_at = clock_timestamp() - interval '2159 hours' WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession)).andExpect(status().isOk());
        jdbc.update("UPDATE tables SET archived_at = clock_timestamp() - interval '2160 hours' WHERE id = ?", table);
        mvc.perform(get("/chat/" + table + "/messages").cookie(memberSession)).andExpect(status().isNotFound());
        assertThat(json.readTree(mvc.perform(get("/tables/archives").cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE id = ?", Integer.class, message))
                .isEqualTo(1);
    }

    @Test
    void tableArchiveRejectsMembershipRaceAndRollsBackOnExpiry() throws Exception {
        UUID host = UUID.randomUUID();
        UUID added = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'archracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'archracemember', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, added, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'A8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        Jwt validated = decoder.decode(loginAs("archracehost").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, host);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, added);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var archive = executor.submit(() -> tableArchives.archive(validated, table, "race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id, status FROM users WHERE id = ANY%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> archive.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TableArchiveService.MembershipChanged.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        verifyMutationExpiry(validated, jwt -> tableArchives.archive(jwt, table, "archive-expiry"));
        assertThat(jdbc.queryForObject("SELECT archived_at IS NULL FROM tables WHERE id = ?",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_archive_members WHERE table_id = ?",
                Integer.class, table)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, table.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                Integer.class, host)).isZero();
    }

    @Test
    void memberRemovalProtectsUnsettledBetsAndReplaysWithoutRemovingAgain() throws Exception {
        UUID host = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'rmhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rmtarget', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'rmoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, target, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Removal', 'T', 'M8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, host, table, target);
        Cookie hostSession = loginAs("rmhost");
        Cookie targetSession = loginAs("rmtarget");
        Cookie outsideSession = loginAs("rmoutside");
        Csrf csrf = csrf();
        String path = "/tables/" + table + "/members/" + target;
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, outsideSession, csrf, "outside", null))
                .andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "host-target", null))
                .andExpect(status().isConflict());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "body", "{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, path).cookie(hostSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, "/tables/" + table + "/members/" + host,
                        hostSession, csrf, "self", null)).andExpect(status().isConflict());
        jdbc.update("UPDATE table_members SET role = 'MEMBER' WHERE table_id = ? AND user_id = ?", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, targetSession, csrf, "non-host", null))
                .andExpect(status().isForbidden());
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, target, outsider);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun, locks_at)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb, 100, false, now())
                """, bet, table, target);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, target);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, target);
        MvcResult blocked = mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("member_removal_unsettled");
        jdbc.update("UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'proposer' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET status = 'SETTLED' WHERE id = ?", bet);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", bet);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents, is_4fun,
                                  locks_at, status, locked_at, remainder_rule)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'For fun?', '["Yes","No"]'::jsonb, 0, true,
                        now(), 'SETTLED', now(), 'proposer'),
                       (?, ?, ?, 'u2pick', '{}'::jsonb, 'Washed?', '["Yes","No"]'::jsonb, 100, false,
                        now(), 'WASHED', now(), 'proposer')
                """, UUID.randomUUID(), table, host, UUID.randomUUID(), table, host);
        jdbc.update("INSERT INTO chat_read_positions (table_id, user_id, last_read_order) VALUES (?, ?, 1)", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                String.class, bet, target)).isEqualTo("Yes");
        assertThat(jdbc.queryForObject("SELECT last_read_order FROM chat_read_positions WHERE table_id = ? AND user_id = ?",
                Long.class, table, target)).isEqualTo(1L);
        mvc.perform(get("/tables/" + table).cookie(targetSession)).andExpect(status().isNotFound());
        mvc.perform(get("/chat/" + table + "/messages").cookie(targetSession)).andExpect(status().isNotFound());
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove", null))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'table.member.removed.v1'
                    AND payload->>'removedUserId' = ?
                """, Integer.class, table.toString(), target.toString())).isEqualTo(1);
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", target);
        mvc.perform(quickPickRequest(HttpMethod.DELETE, path, hostSession, csrf, "remove-frozen", null))
                .andExpect(status().isNoContent());
    }

    @Test
    void hostChangesPreserveActiveHostCancelInvitesAndAttributeChatSafely() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostactor', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hosttarget', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'hostoutsider', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinvitee', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, target, hash, frozen, hash, outsider, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Hosts', 'T', 'H4R7QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_text, can_react)
                VALUES (?, ?, 'HOST', true, true), (?, ?, 'MEMBER', false, false), (?, ?, 'MEMBER', true, true)
                """, table, actor, table, target, table, frozen);
        Cookie actorSession = loginAs("hostactor");
        Cookie targetSession = loginAs("hosttarget");
        Cookie outsideSession = loginAs("hostoutsider");
        Jwt validated = decoder.decode(actorSession.getValue());
        Csrf csrf = csrf();
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "anonymous")).andExpect(status().isUnauthorized());
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(actorSession)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "no-csrf"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/tables/" + table + "/hosts/" + target).cookie(actorSession, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "bad-body").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, targetSession, csrf, "member"))
                .andExpect(status().isForbidden());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, outsideSession, csrf, "outsider"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.PUT, table, outsider, actorSession, csrf, "not-member"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.PUT, table, UUID.randomUUID(), actorSession, csrf, "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, actor))
                .isZero();
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, actorSession, csrf, "already-host"))
                .andExpect(status().isNoContent());
        MvcResult promoted = mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(promoted.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("""
                SELECT role = 'HOST' AND can_bet AND can_text AND can_react AND can_send_gifs
                FROM table_members WHERE table_id = ? AND user_id = ?
                """, Boolean.class, table, target)).isTrue();
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isConflict());
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, target);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, target, recipient);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, config, question, choices, wager_cents,
                                  is_4fun, locks_at, status, locked_at, remainder_rule, remainder_host_id)
                VALUES (?, ?, ?, 'u2pick', '{}'::jsonb, 'Question?', '["Yes","No"]'::jsonb,
                        100, false, now(), 'PENDING', now(), 'host', ?)
                """, bet, table, actor, target);
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "demote"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, bet))
                .isEqualTo(target);
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                SELECT role = 'MEMBER' AND can_bet AND can_text AND can_react AND can_send_gifs
                FROM table_members WHERE table_id = ? AND user_id = ?
                """, Boolean.class, table, target)).isTrue();
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "already-member"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote-again"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        mvc.perform(hostRequest(HttpMethod.PUT, table, frozen, actorSession, csrf, "promote-frozen"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, target, actorSession, csrf, "demote-again"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", frozen);
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isNoContent());
        mvc.perform(hostRequest(HttpMethod.DELETE, table, actor, actorSession, csrf, "last"))
                .andExpect(status().isForbidden());
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, actorSession, csrf, "promote"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?", Integer.class, table))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ?
                  AND event_type IN ('table.host.changed.v1', 'chat.message.created.v1')
                """, Integer.class, table.toString())).isEqualTo(12);
        jdbc.update("UPDATE users SET username = 'hostrenamed' WHERE id = ?", target);
        JsonNode renamed = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(actorSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(renamed.path("items").get(0).path("text").asText()).isEqualTo("hostrenamed is now a host");
        assertThat(renamed.path("items").get(1).path("text").asText()).isEqualTo("hostrenamed is no longer a host");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM chat_messages WHERE table_id = ? AND system_subject_id = ? AND text LIKE '%hosttarget%'
                """, Integer.class, table, target)).isZero();
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(), username = NULL, hue = NULL,
                    password_hash = NULL WHERE id = ?
                """, target);
        JsonNode deleted = json.readTree(mvc.perform(get("/chat/" + table + "/messages").cookie(actorSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(deleted.path("items").get(0).path("text").asText()).isEqualTo("Deleted user is now a host");
        assertThat(deleted.path("items").get(1).path("text").asText()).isEqualTo("Deleted user is no longer a host");
        assertThat(deleted.toString()).doesNotContain("hosttarget", "hostrenamed");
        Cookie formerFrozenSession = loginAs("hostfrozen");
        mvc.perform(hostRequest(HttpMethod.PUT, table, target, formerFrozenSession, csrf, "deleted-target"))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_subject_id)
                VALUES (?, ?, 'system', 'Invalid', ?)
                """, UUID.randomUUID(), table, actor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO chat_messages (id, table_id, kind, text, system_event, system_subject_id)
                VALUES (?, ?, 'system', 'hosttarget is now a host', 'host_promoted', ?)
                """, UUID.randomUUID(), table, actor)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(hostRequest(HttpMethod.PUT, table, actor, formerFrozenSession, csrf, "archived"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", actor);
        assertThat(tableHosts.set(validated, table, actor, true, "already-host"))
                .isEqualTo(TableHostService.Outcome.UNAUTHORIZED);
    }

    @Test
    void concurrentHostStepDownsRetainAnActiveHost() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, first, hash, second, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'H4R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, first, table, second);
        Jwt firstJwt = decoder.decode(loginAs("hostracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("hostraceb").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return tableHosts.set(firstJwt, table, first, false, "step-down");
                } catch (TableHostService.LastActiveHost exception) {
                    return TableHostService.Outcome.CONFLICT;
                }
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return tableHosts.set(secondJwt, table, second, false, "step-down");
                } catch (TableHostService.LastActiveHost exception) {
                    return TableHostService.Outcome.CONFLICT;
                }
            });
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(TableHostService.Outcome.UPDATED, TableHostService.Outcome.CONFLICT);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM table_members m JOIN users u ON u.id = m.user_id
                    WHERE m.table_id = ? AND m.role = 'HOST' AND u.status = 'ACTIVE'
                    """, Integer.class, table)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostDemotionAndInvitationAcceptanceSerialize() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostinvitea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinviteb', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostinvitec', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, sender, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'H4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, sender);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, sender);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, sender, recipient);
        Jwt actorJwt = decoder.decode(loginAs("hostinvitea").getValue());
        Jwt recipientJwt = decoder.decode(loginAs("hostinvitec").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var demote = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableHosts.set(actorJwt, table, sender, false, "demote-race");
            });
            var accept = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitationResponses.respond(recipientJwt, invite, true, "accept-race");
            });
            start.countDown();
            assertThat(demote.get(10, TimeUnit.SECONDS)).isEqualTo(TableHostService.Outcome.UPDATED);
            InvitationResponseService.Outcome outcome = accept.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isIn(InvitationResponseService.Outcome.UPDATED, InvitationResponseService.Outcome.NOT_FOUND,
                    InvitationResponseService.Outcome.STALE);
            boolean accepted = outcome == InvitationResponseService.Outcome.UPDATED;
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo(accepted ? "ACCEPTED" : "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                    Integer.class, table, recipient)).isEqualTo(accepted ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostStepDownObservesCoordinatedConcurrentFreeze() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostfreezea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostfreezeb', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Freeze', 'T', 'H4F9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, other);
        Jwt actorJwt = decoder.decode(loginAs("hostfreezea").getValue());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var freeze = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, other);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var stepDown = executor.submit(() -> tableHosts.set(actorJwt, table, actor, false, "freeze-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            freeze.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stepDown.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(TableHostService.LastActiveHost.class);
            assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                    String.class, table, actor)).isEqualTo("HOST");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void hostChangeExpiryRollsBackRoleInviteMessageAndClaim() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'hostexpirea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostexpireb', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'hostexpirec', 1, ?, 'PLAYER', 'ACTIVE')
                """, actor, hash, target, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Expiry', 'T', 'H4X9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, actor, table, target);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, target, recipient);
        Jwt validated = decoder.decode(loginAs("hostexpirea").getValue());
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
                    .subject(actor.toString()).claim("jti", validated.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(1)).expiresAt(expiry).build();
            var update = executor.submit(() -> tableHosts.set(expiring, table, target, false, "expiry"));
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
            assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                    String.class, table, target)).isEqualTo("HOST");
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, actor)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                    Integer.class, table.toString())).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableCreationAndMemberRequestsRespectPreferencesAndReplay() throws Exception {
        UUID host = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        UUID closed = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'createhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfriend', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfollower', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createclosed', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'createfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'createblocked', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, friend, hash, follower, hash, closed, hash, frozen, hash, blocked, hash);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, friend, host, host, friend, follower, host, closed, host, frozen, host, blocked, host);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocked, host);
        jdbc.update("INSERT INTO invite_preferences (user_id, following) VALUES (?, 'closed')", closed);
        Cookie session = loginAs("createhost");
        Csrf csrf = csrf();
        var input = json.createObjectNode().put("name", "  Weekend  ").put("emoji", "🏈");
        var ids = input.putArray("memberIds");
        for (UUID id : List.of(friend, follower, closed, frozen, blocked, missing, host)) ids.add(id.toString());
        String body = json.writeValueAsString(input);
        MvcResult created = mvc.perform(tablePost("/tables", session, csrf, "create-table", body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode response = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("TableCreationResult").validate(response)).isEmpty();
        UUID table = UUID.fromString(response.path("table").path("id").asText());
        assertThat(response.path("table").path("name").asText()).isEqualTo("Weekend");
        assertThat(response.path("outcomes").findValuesAsText("outcome"))
                .containsExactly("added", "invited", "not_added", "not_added", "not_added", "not_added", "not_added");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ?",
                Integer.class, table)).isEqualTo(2);
        UUID invite = jdbc.queryForObject("SELECT id FROM table_invites WHERE table_id = ? AND user_id = ?",
                UUID.class, table, follower);
        assertThat(invite).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND text ILIKE '%invit%'",
                Integer.class, table)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_type = 'user' AND aggregate_id = ?
                    AND event_type = 'table.invitation.created.v1'
                """, Integer.class, follower.toString())).isEqualTo(1);
        mvc.perform(invitationResponse(invite, loginAs("createfollower"), csrf, "accept-created-invite", true))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE tables SET name = 'Renamed' WHERE id = ?", table);
        MvcResult replay = mvc.perform(tablePost("/tables", session, csrf, "create-table", body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode replayed = json.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.path("table").path("name").asText()).isEqualTo("Renamed");
        assertThat(replayed.path("table").path("memberIds").size()).isEqualTo(3);
        assertThat(replayed.path("outcomes")).isEqualTo(response.path("outcomes"));
        mvc.perform(tablePost("/tables", session, csrf, "create-table", body.replace("Weekend", "Other")))
                .andExpect(status().isConflict());
        mvc.perform(tablePost("/tables", session, csrf, "bad", "{\"name\":\"X\",\"emoji\":\"🏈\",\"memberIds\":[\""
                + friend + "\",\"" + friend + "\"]}")).andExpect(status().isBadRequest());
        mvc.perform(tablePost("/tables", session, csrf, "bad", "{\"name\":\"X\",\"emoji\":\"no\"}"))
                .andExpect(status().isBadRequest());
        var oversized = json.createObjectNode().put("name", "X").put("emoji", "🏈");
        var tooMany = oversized.putArray("memberIds");
        for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID().toString());
        mvc.perform(tablePost("/tables", session, csrf, "bad", json.writeValueAsString(oversized)))
                .andExpect(status().isBadRequest());
        tooMany.remove(100);
        MvcResult boundary = mvc.perform(tablePost("/tables", session, csrf, "limit", json.writeValueAsString(oversized)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(boundary.getResponse().getContentAsString()).path("outcomes").size()).isEqualTo(100);

        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, friend);
        var add = json.createObjectNode();
        add.putArray("userIds").add(friend.toString()).add(closed.toString());
        String addBody = json.writeValueAsString(add);
        String path = "/tables/" + table + "/members";
        MvcResult firstAdd = mvc.perform(tablePost(path, session, csrf, "add-members", addBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode addResponse = json.readTree(firstAdd.getResponse().getContentAsString());
        for (JsonNode member : addResponse) {
            assertThat(OpenApiSchemas.load("MemberRequestOutcome").validate(member)).isEmpty();
        }
        assertThat(addResponse.findValuesAsText("outcome"))
                .containsExactly("added", "not_added");
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, friend);
        MvcResult addReplay = mvc.perform(tablePost(path, session, csrf, "add-members", addBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(addReplay.getResponse().getContentAsString()))
                .isEqualTo(json.readTree(firstAdd.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, table, friend)).isZero();
        mvc.perform(tablePost(path, session, csrf, "add-members", "{\"userIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(tablePost(path, loginAs("createfollower"), csrf, "not-host", addBody))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE table_members SET role = 'HOST' WHERE table_id = ? AND user_id = ?", table, follower);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, host);
        mvc.perform(tablePost(path, session, csrf, "add-members", addBody)).andExpect(status().isNotFound());
        mvc.perform(tablePost("/tables", session, csrf, "create-table", body)).andExpect(status().isNotFound());
    }

    @Test
    void concurrentTableCreationRetriesCreateOnlyOneTable() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'createretry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("createretry").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.create(jwt, "same-create", "Retry", "🏈", List.of());
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.create(jwt, "same-create", "Retry", "🏈", List.of());
            });
            start.countDown();
            var one = first.get(10, TimeUnit.SECONDS);
            var two = second.get(10, TimeUnit.SECONDS);
            assertThat(one.outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(two.outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(one.tableId()).isEqualTo(two.tableId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE user_id = ?",
                    Integer.class, user)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, one.tableId())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentTableInvitationAndBlockCannotLeavePendingInvitation() throws Exception {
        UUID host = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'create_racehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'create_racepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'T9S7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        Jwt hostJwt = decoder.decode(loginAs("create_racehost").getValue());
        Jwt recipientJwt = decoder.decode(loginAs("create_racepeer").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var add = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return tableCreation.add(hostJwt, table, "race-add", List.of(recipient));
            });
            var block = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(recipientJwt, host, "race-block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            assertThat(add.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(TableCreationService.Outcome.SUCCESS);
            assertThat(block.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM table_invites WHERE table_id = ? AND user_id = ? AND status = 'PENDING'
                    """, Integer.class, table, recipient)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void tableMemberRequestRollsBackWhenSessionExpiresWhileWaiting() throws Exception {
        UUID host = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'create_expire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'create_expeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, recipient, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Wait', 'T', 'T9S8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        Jwt validated = decoder.decode(loginAs("create_expire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
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
            var add = executor.submit(() -> tableCreation.add(expiring, table, "expired-add", List.of(recipient)));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %FOR UPDATE%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> add.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_invites WHERE table_id = ?",
                    Integer.class, table)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, host)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ?",
                    Integer.class, table)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tablePost(
            String path, Cookie session, Csrf csrf, String key, String body) {
        return post(path).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder hostRequest(
            HttpMethod method, UUID table, UUID target, Cookie session, Csrf csrf, String key) {
        return request(method, "/tables/" + table + "/hosts/" + target).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }
}
