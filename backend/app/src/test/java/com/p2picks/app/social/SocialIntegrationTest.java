package com.p2picks.app.social;

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
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
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

class SocialIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private InvitationResponseService invitationResponses;

    @Autowired
    private InvitePreferencesService invitePreferences;

    @Autowired
    private InvitePreferencesController invitePreferencesController;

    @Autowired
    private SocialMutationService socialMutations;

    @Autowired
    private SocialReadController socialReads;

    @Test
    void relationshipReadsKeepBlocksPrivateAndFilterStaleFollows() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID blocker = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'relviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relfriend', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relblocked', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relblocker', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'relfollower', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, friend, hash, blocked, hash, blocker, hash, follower, hash);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES
                (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)
                """, viewer, friend, friend, viewer, viewer, blocked, blocked, viewer,
                viewer, blocker, blocker, viewer, follower, viewer);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, blocked, blocker, viewer);

        mvc.perform(get("/me/relationships")).andExpect(status().isUnauthorized());
        Cookie viewerSession = loginAs("relviewer");
        Cookie blockedSession = loginAs("relblocked");
        MvcResult response = mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode relationships = json.readTree(response.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Relationships").validate(relationships)).isEmpty();
        assertThat(relationships.path("followingIds").size()).isEqualTo(1);
        assertThat(relationships.path("followingIds").get(0).asText()).isEqualTo(friend.toString());
        assertThat(relationships.path("followerIds").size()).isEqualTo(2);
        assertThat(relationships.path("followerIds").toString()).contains(friend.toString(), follower.toString());
        assertThat(relationships.path("blockedIds").size()).isEqualTo(1);
        assertThat(relationships.path("blockedIds").get(0).asText()).isEqualTo(blocked.toString());
        assertThat(relationships.toString()).doesNotContain(blocker.toString());

        JsonNode blockedView = json.readTree(mvc.perform(get("/me/relationships").cookie(blockedSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(blockedView.path("blockedIds").isEmpty()).isTrue();
        assertThat(blockedView.path("followingIds").isEmpty()).isTrue();
        assertThat(blockedView.path("followerIds").isEmpty()).isTrue();

        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", follower);
        relationships = json.readTree(mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(relationships.path("followerIds").toString()).contains(follower.toString());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(),
                    username = NULL, hue = NULL, password_hash = NULL WHERE id IN (?, ?)
                """, follower, blocked);
        relationships = json.readTree(mvc.perform(get("/me/relationships").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(relationships.path("followerIds").size()).isEqualTo(1);
        assertThat(relationships.path("followerIds").get(0).asText()).isEqualTo(friend.toString());
        assertThat(relationships.path("blockedIds").isEmpty()).isTrue();
        Jwt validatedBeforeRevocation = decoder.decode(viewerSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(socialReads.relationships(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/relationships").cookie(viewerSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void invitationReadsOnlyReturnVisiblePendingInvitesWithoutTableAccess() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID outgoingBlocked = UUID.randomUUID();
        UUID incomingBlocked = UUID.randomUUID();
        UUID deletedSender = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'invitesender', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteblocked', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteblocker', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'invitedeleted', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'inviteother', 1, ?, 'PLAYER', 'ACTIVE')
                """, viewer, hash, sender, hash, outgoingBlocked, hash, incomingBlocked, hash,
                deletedSender, hash, other, hash);
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        UUID blockedTable = UUID.randomUUID();
        UUID blockerTable = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID alreadyMember = UUID.randomUUID();
        UUID deletedTable = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Older', 'T', 'P3R5QA'), (?, 'Newer', 'T', 'Q3R5QA'),
                (?, 'Blocked', 'T', 'R3R5QA'), (?, 'Blocker', 'T', 'S3R5QA'),
                (?, 'Archived', 'T', 'T3R5QA'), (?, 'Member', 'T', 'U3R5QA'),
                (?, 'Deleted', 'T', 'V3R5QA'), (?, 'Accepted', 'T', 'W3R5QA')
                """, older, newer, blockedTable, blockerTable, archived, alreadyMember, deletedTable, accepted);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                alreadyMember, viewer);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                older, sender, newer, sender);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", viewer, sender);
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?), (?, ?)",
                viewer, outgoingBlocked, incomingBlocked, viewer);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status, created_at) VALUES
                (?, ?, ?, ?, 'PENDING', now() - interval '1 minute'),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'PENDING', now()),
                (?, ?, ?, ?, 'ACCEPTED', now())
                """, UUID.randomUUID(), older, sender, viewer, UUID.randomUUID(), newer, sender, viewer,
                UUID.randomUUID(), blockedTable, outgoingBlocked, viewer,
                UUID.randomUUID(), blockerTable, incomingBlocked, viewer,
                UUID.randomUUID(), archived, sender, viewer,
                UUID.randomUUID(), alreadyMember, sender, viewer,
                UUID.randomUUID(), deletedTable, deletedSender, viewer,
                UUID.randomUUID(), accepted, sender, viewer);
        jdbc.update("""
                UPDATE users SET status = 'DELETED', deleted_at = now(),
                    username = NULL, hue = NULL, password_hash = NULL WHERE id = ?
                """, deletedSender);
        mvc.perform(get("/me/invites")).andExpect(status().isUnauthorized());
        Cookie viewerSession = loginAs("inviteviewer");
        Cookie otherSession = loginAs("inviteother");
        MvcResult response = mvc.perform(get("/me/invites").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode invitations = json.readTree(response.getResponse().getContentAsString());
        assertThat(invitations.size()).isEqualTo(2);
        assertThat(invitations.get(0).path("tableId").asText()).isEqualTo(newer.toString());
        assertThat(invitations.get(1).path("tableId").asText()).isEqualTo(older.toString());
        assertThat(invitations.get(0).path("tableName").asText()).isEqualTo("Newer");
        assertThat(invitations.get(0).path("userId").asText()).isEqualTo(viewer.toString());
        for (JsonNode invitation : invitations) {
            assertThat(OpenApiSchemas.load("TableInvite").validate(invitation)).isEmpty();
            assertThat(invitation.has("quickPickCode")).isFalse();
            assertThat(invitation.has("memberIds")).isFalse();
        }
        mvc.perform(get("/tables/" + newer).cookie(viewerSession)).andExpect(status().isNotFound());
        JsonNode otherInvites = json.readTree(mvc.perform(get("/me/invites").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherInvites.isEmpty()).isTrue();
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", sender);
        invitations = json.readTree(mvc.perform(get("/me/invites").cookie(viewerSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(invitations.size()).isEqualTo(2);
        Jwt validatedBeforeRevocation = decoder.decode(viewerSession.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", viewer);
        assertThat(socialReads.invitations(validatedBeforeRevocation).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/invites").cookie(viewerSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentIdempotencyAndKeyChangesFailSafely() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'prefconcurrent', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("prefconcurrent").getValue());
        var preferences = new InvitePreferencesService.Preferences("open", "request");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "concurrent", preferences);
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "concurrent", preferences);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(InvitePreferencesService.Outcome.UPDATED,
                            InvitePreferencesService.Outcome.REPLAYED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            Jwt expired = Jwt.withTokenValue("expired").header("alg", "HS256")
                    .subject(user.toString()).claim("jti", jwt.getId())
                    .issuedAt(java.time.Instant.now().minusSeconds(10))
                    .expiresAt(java.time.Instant.now().minusSeconds(1)).build();
            assertThatThrownBy(() -> invitePreferences.replace(expired, "expired", preferences))
                    .isInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
            IdempotencyGuard changedKey = new IdempotencyGuard(jdbc, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCA=");
            TransactionTemplate tx = new TransactionTemplate(transactions);
            assertThatThrownBy(() -> tx.execute(status -> changedKey.claim(user, "concurrent",
                    "invite-preferences.replace.v1", preferences.friends() + "|" + preferences.following())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invitePreferencesReadAndReplaceArePrivateAndRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invitation = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteprefs', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'prefspeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Invite', 'T', 'T7R6QA')", table);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invitation, table, other, user);
        Cookie session = loginAs("inviteprefs");
        Cookie otherSession = loginAs("prefspeer");
        Csrf csrf = csrf();
        mvc.perform(get("/me/invite-preferences")).andExpect(status().isUnauthorized());
        MvcResult defaults = mvc.perform(get("/me/invite-preferences").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(defaults.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode state = json.readTree(defaults.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("InvitePreferences").validate(state)).isEmpty();
        assertThat(state.path("friends").asText()).isEqualTo("open");
        assertThat(state.path("following").asText()).isEqualTo("request");
        assertThat(state.size()).isEqualTo(2);
        mvc.perform(put("/me/invite-preferences").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friends\":\"closed\",\"following\":\"open\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/invite-preferences").cookie(session)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "invite-first")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friends\":\"closed\",\"following\":\"open\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(invitePreferencesPut(session, csrf, "bad", "{\"friends\":\"closed\",\"following\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(invitePreferencesPut(session, csrf, "bad", "{\"friends\":\"open\",\"following\":\"open\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                Integer.class, user)).isZero();
        String first = "{\"friends\":\"closed\",\"following\":\"open\"}";
        String second = "{\"friends\":\"request\",\"following\":\"closed\"}";
        MvcResult saved = mvc.perform(invitePreferencesPut(session, csrf, "invite-first", first))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(invitePreferencesPut(session, csrf, "invite-second", second)).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "invite-first",
                "{\"following\":\"open\",\"friends\":\"closed\"}")).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "invite-first", second)).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "invite-first", "{\"capCents\":0}")).andExpect(status().isConflict());
        state = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("InvitePreferences").validate(state)).isEmpty();
        assertThat(state.path("friends").asText()).isEqualTo("request");
        assertThat(state.path("following").asText()).isEqualTo("closed");
        JsonNode otherState = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherState.path("friends").asText()).isEqualTo("open");
        assertThat(otherState.path("following").asText()).isEqualTo("request");
        jdbc.update("INSERT INTO invite_preferences (user_id) VALUES (?)", other);
        JsonNode storedDefaults = json.readTree(mvc.perform(get("/me/invite-preferences").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(storedDefaults).isEqualTo(otherState);
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invitation))
                .isEqualTo("CANCELLED");
        assertThatThrownBy(() -> jdbc.update("UPDATE invite_preferences SET friends = 'invalid' WHERE user_id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        Jwt validated = decoder.decode(session.getValue());
        assertThatThrownBy(() -> invitePreferences.replace(validated, "db-invalid",
                new InvitePreferencesService.Preferences("invalid", "open")))
                .isInstanceOf(DataIntegrityViolationException.class);
        Jwt expired = Jwt.withTokenValue("expired").header("alg", "HS256")
                .subject(user.toString()).claim("jti", validated.getId())
                .issuedAt(java.time.Instant.now().minusSeconds(10))
                .expiresAt(java.time.Instant.now().minusSeconds(1)).build();
        assertThatThrownBy(() -> invitePreferences.replace(expired, "expired",
                new InvitePreferencesService.Preferences("open", "open")))
                .isInstanceOf(MutationAuthentication.ExpiredSession.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2);
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        assertThat(invitePreferences.replace(validated, "invite-first",
                new InvitePreferencesService.Preferences("closed", "open")))
                .isEqualTo(InvitePreferencesService.Outcome.UNAUTHORIZED);
        assertThat(invitePreferencesController.invitePreferences(validated).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/invite-preferences").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentInvitePreferenceRequestsWithDifferentPayloadsConflict() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'inviterace', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        Jwt jwt = decoder.decode(loginAs("inviterace").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "same-key", new InvitePreferencesService.Preferences("open", "closed"));
            });
            var second = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitePreferences.replace(jwt, "same-key", new InvitePreferencesService.Preferences("closed", "open"));
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(InvitePreferencesService.Outcome.UPDATED, InvitePreferencesService.Outcome.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void socialMutationsEnforcePrivateBlocksAndRetrySafety() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID otherTable = UUID.randomUUID();
        UUID incoming = UUID.randomUUID();
        UUID outgoing = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialwriter', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialpeer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialthird', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash, third, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Social', 'T', 'T8S4QA'), (?, 'Other', 'T', 'T8S5QA')
                """, table, otherTable);
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)
                """, user, other, other, user);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES
                (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, incoming, table, other, user, outgoing, table, user, other,
                unrelated, otherTable, third, user);
        Cookie session = loginAs("socialwriter");
        Cookie otherSession = loginAs("socialpeer");
        Csrf csrf = csrf();
        mvc.perform(post("/blocks/" + other).cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/blocks/" + other).cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "block"))
                .andExpect(status().isForbidden());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "bad-body")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        MvcResult blocked = mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "block"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(blocked.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                    OR (follower_id = ? AND followee_id = ?)
                """, Integer.class, user, other, other, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, incoming))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, outgoing))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, unrelated))
                .isEqualTo("PENDING");
        MvcResult denied = mvc.perform(socialRequest(HttpMethod.POST, "/follows/", user, otherSession, csrf, "follow"))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult absent = mvc.perform(socialRequest(HttpMethod.POST, "/follows/", UUID.randomUUID(),
                otherSession, csrf, "follow")).andExpect(status().isNotFound()).andReturn();
        assertThat(denied.getResponse().getContentAsString()).isEqualTo(absent.getResponse().getContentAsString());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNotFound());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", user, otherSession, csrf, "reverse"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/blocks/", other, session, csrf, "unblock"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "block"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, user, other)).isZero();
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNotFound());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/blocks/", user, otherSession, csrf, "unblock"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", third, session, csrf, "follow"))
                .andExpect(status().isConflict());
        mvc.perform(socialRequest(HttpMethod.POST, "/blocks/", other, session, csrf, "follow"))
                .andExpect(status().isConflict());
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", other, session, csrf, "unfollow"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "follow"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follows WHERE follower_id = ? AND followee_id = ?",
                Integer.class, user, other)).isZero();
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", user, session, csrf, "self"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", other);
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "frozen-follow"))
                .andExpect(status().isNoContent());
        jdbc.update("""
                UPDATE users SET status = 'DELETED', username = NULL, hue = NULL,
                    password_hash = NULL, deleted_at = now() WHERE id = ?
                """, other);
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", other, session, csrf, "frozen-follow"))
                .andExpect(status().isNotFound());
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(socialMutations.mutate(validated, third, "anything", SocialMutationService.Operation.FOLLOW))
                .isEqualTo(SocialMutationService.Outcome.UNAUTHORIZED);
    }

    @Test
    void concurrentSocialMutationsUseConsistentPairLocking() throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialracea', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, firstId, hash, secondId, hash);
        Jwt firstJwt = decoder.decode(loginAs("socialracea").getValue());
        Jwt secondJwt = decoder.decode(loginAs("socialraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var follow = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(firstJwt, secondId, "follow", SocialMutationService.Operation.FOLLOW);
            });
            var firstBlock = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(firstJwt, secondId, "block", SocialMutationService.Operation.BLOCK);
            });
            var secondBlock = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(secondJwt, firstId, "block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            assertThat(follow.get(10, TimeUnit.SECONDS)).isIn(
                    SocialMutationService.Outcome.UPDATED, SocialMutationService.Outcome.NOT_FOUND);
            assertThat(firstBlock.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(secondBlock.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                        OR (follower_id = ? AND followee_id = ?)
                    """, Integer.class, firstId, secondId, secondId, firstId)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM blocks WHERE (blocker_id = ? AND blocked_id = ?)
                        OR (blocker_id = ? AND blocked_id = ?)
                    """, Integer.class, firstId, secondId, secondId, firstId)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void blockRollsBackAllEffectsIfSessionExpiresWhileWaiting() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'socialexpire', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'socialexppeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Social', 'T', 'T8S6QA')", table);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)", user, other, other, user);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, invite, table, user, other);
        Jwt validated = decoder.decode(loginAs("socialexpire").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM table_invites WHERE id = ? FOR UPDATE", UUID.class, invite);
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
            var mutation = executor.submit(() ->
                    socialMutations.mutate(expiring, other, "expire-block", SocialMutationService.Operation.BLOCK));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'UPDATE table_invites SET status = %'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> mutation.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                    Integer.class, user, other)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM follows WHERE (follower_id = ? AND followee_id = ?)
                        OR (follower_id = ? AND followee_id = ?)
                    """, Integer.class, user, other, other, user)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?",
                    Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void invitationResponsesAndEligibilityChangesAreTransactionalAndRetrySafe() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID declineTable = UUID.randomUUID();
        UUID staleTable = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        UUID decline = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'respondviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'respondhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Response', 'T', 'Y4R6QA'), (?, 'Decline', 'T', 'Y4R7QA'),
                (?, 'Stale', 'T', 'Y4R8QA')
                """, table, declineTable, staleTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'HOST'), (?, ?, 'HOST')
                """, table, host, declineTable, host, staleTable, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("""
                INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES
                (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING'), (?, ?, ?, ?, 'PENDING')
                """, invite, table, host, recipient, decline, declineTable, host, recipient,
                stale, staleTable, host, recipient);
        Cookie session = loginAs("respondviewer");
        Cookie hostSession = loginAs("respondhost");
        Csrf csrf = csrf();
        mvc.perform(invitePreferencesPut(session, csrf, "open-invites",
                "{\"friends\":\"closed\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, hostSession, csrf, "wrong-user", true))
                .andExpect(status().isNotFound());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(invite, session, csrf, "accept", false)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                String.class, table, recipient)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE table_id = ? AND kind = 'system'",
                Integer.class, table)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Integer.class, table.toString())).isEqualTo(2);
        mvc.perform(invitationResponse(decline, session, csrf, "decline", false)).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(decline, session, csrf, "decline", false)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, decline))
                .isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                Integer.class, declineTable, recipient)).isZero();
        mvc.perform(invitePreferencesPut(session, csrf, "close-invites",
                "{\"friends\":\"open\",\"following\":\"closed\"}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, stale))
                .isEqualTo("CANCELLED");
        mvc.perform(invitePreferencesPut(session, csrf, "reopen-invites",
                "{\"friends\":\"open\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(invitationResponse(stale, session, csrf, "stale", false)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, stale))
                .isEqualTo("CANCELLED");
        UUID noHost = UUID.randomUUID();
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                noHost, staleTable, host, recipient);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", staleTable, host);
        mvc.perform(invitationResponse(noHost, session, csrf, "no-host", true)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, noHost))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ? AND operation = ?",
                Integer.class, recipient, "invitation.respond.v1")).isEqualTo(2);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, recipient);
        mvc.perform(invitationResponse(invite, session, csrf, "accept", true)).andExpect(status().isNotFound());
    }

    @Test
    void followChangesCancelIneligibleInvitationsWithoutRestoringThem() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'followinvitee', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'followinviter', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Follow', 'T', 'Y4R9QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, recipient);
        Cookie session = loginAs("followinvitee");
        Cookie hostSession = loginAs("followinviter");
        Csrf csrf = csrf();
        mvc.perform(invitePreferencesPut(session, csrf, "close-friends",
                "{\"friends\":\"closed\",\"following\":\"open\"}")).andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", recipient, hostSession, csrf, "make-friends"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                .isEqualTo("CANCELLED");
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", recipient, hostSession, csrf, "stop-friends"))
                .andExpect(status().isNoContent());
        UUID secondInvite = UUID.randomUUID();
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                secondInvite, table, host, recipient);
        mvc.perform(socialRequest(HttpMethod.DELETE, "/follows/", host, session, csrf, "unfollow-invite"))
                .andExpect(status().isNoContent());
        mvc.perform(socialRequest(HttpMethod.POST, "/follows/", host, session, csrf, "refollow-invite"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, secondInvite))
                .isEqualTo("CANCELLED");
    }

    @Test
    void concurrentInvitationAcceptanceAndBlockSerialize() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'inviteracea', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'inviteraceb', 1, ?, 'PLAYER', 'ACTIVE')
                """, recipient, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'Y5R6QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", recipient, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, recipient);
        Jwt recipientJwt = decoder.decode(loginAs("inviteracea").getValue());
        Jwt hostJwt = decoder.decode(loginAs("inviteraceb").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var response = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return invitationResponses.respond(recipientJwt, invite, true, "race-accept");
            });
            var block = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return socialMutations.mutate(hostJwt, recipient, "race-block", SocialMutationService.Operation.BLOCK);
            });
            start.countDown();
            InvitationResponseService.Outcome outcome = response.get(10, TimeUnit.SECONDS);
            assertThat(outcome).isIn(InvitationResponseService.Outcome.UPDATED, InvitationResponseService.Outcome.NOT_FOUND);
            assertThat(block.get(10, TimeUnit.SECONDS)).isEqualTo(SocialMutationService.Outcome.UPDATED);
            assertThat(jdbc.queryForObject("SELECT status FROM table_invites WHERE id = ?", String.class, invite))
                    .isEqualTo(outcome == InvitationResponseService.Outcome.UPDATED ? "ACCEPTED" : "CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM table_members WHERE table_id = ? AND user_id = ?",
                    Integer.class, table, recipient)).isEqualTo(outcome == InvitationResponseService.Outcome.UPDATED ? 1 : 0);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder socialRequest(
            HttpMethod method, String prefix, UUID target, Cookie session, Csrf csrf, String key) {
        return request(method, prefix + target).cookie(session, csrf.cookie())
                .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                .header("Idempotency-Key", key);
    }
}
