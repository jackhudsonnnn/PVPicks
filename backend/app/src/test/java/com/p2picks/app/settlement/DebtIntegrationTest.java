package com.p2picks.app.settlement;

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

class DebtIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private DebtCapService debtCaps;

    @Autowired
    private InvitationResponseService invitationResponses;

    @Autowired
    private DebtCapController debtCapController;

    @Test
    void debtReadIncludesCurrentExposureAndOpenPersonNetsOnly() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID losing = UUID.randomUUID();
        UUID winning = UUID.randomUUID();
        UUID archived = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtviewer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debthost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'debtpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash, third, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Losing', 'T', 'D6R3QA'), (?, 'Winning', 'T', 'D6R4QA'),
                (?, 'Archived', 'T', 'D6R5QA'), (?, 'Left', 'T', 'D6R6QA')
                """, losing, winning, archived, left);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, losing, user, winning, user, archived, user);
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", archived);
        UUID corrected = insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, -300);
        appendDebtDelta(corrected, losing, user, other, 50);
        insertDebtBet(losing, user, other, "SETTLED", false, false, false, 100, 100);
        insertDebtBet(losing, user, other, "PENDING", false, true, false, 150, 0);
        insertDebtBet(losing, user, other, "PENDING", false, false, false, 700, 0);
        insertDebtBet(losing, user, other, "PENDING", true, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "ACTIVE", false, true, false, 1000, 0);
        insertDebtBet(losing, user, other, "SETTLED", false, false, true, 100, -900);
        insertDebtBet(winning, user, other, "SETTLED", false, false, false, 100, 9000);
        insertDebtBet(archived, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtBet(left, user, other, "SETTLED", false, false, false, 100, -1000);
        insertDebtReceipt(archived, other, user, other, 400, "sent");
        insertDebtReceipt(archived, other, other, user, 100, "unpaid");
        insertDebtReceipt(archived, other, user, other, 10, "unpaid");
        insertDebtReceipt(archived, other, user, other, 20, "disputed");
        insertDebtReceipt(archived, other, user, other, 30, "forgive_asked");
        insertDebtReceipt(archived, third, third, user, 9000, "unpaid");
        insertDebtReceipt(archived, other, user, other, 9000, "paid");
        insertDebtReceipt(archived, other, user, other, 9000, "forgiven");
        mvc.perform(get("/me/debt")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("debtviewer");
        MvcResult response = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode debt = json.readTree(response.getResponse().getContentAsString());
        assertThat(debt.size()).isEqualTo(1);
        assertThat(debt.path("debtCents").asLong()).isEqualTo(660);
        Cookie thirdSession = loginAs("debtpeer");
        JsonNode thirdDebt = json.readTree(mvc.perform(get("/me/debt").cookie(thirdSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(thirdDebt.path("debtCents").asLong()).isEqualTo(9000);
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCapController.debt(validated).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void debtReadUsesExactAggregateArithmeticAndFailsOnUnrepresentableDebt() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'debtexact', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'debtexactpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Exact', 'T', 'D6R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, user);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, 1);
        Cookie session = loginAs("debtexact");
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isZero();
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -Long.MAX_VALUE);
        insertDebtBet(table, user, other, "SETTLED", false, false, false, 100, -100);
        Jwt jwt = decoder.decode(session.getValue());
        assertThatThrownBy(() -> debtCapController.debt(jwt)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void debtCapUpdatesRequireZeroDebtAndAreRetrySafe() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capwriter', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capwriterpeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Cap', 'T', 'D8R3QA')", table);
        Cookie session = loginAs("capwriter");
        Csrf csrf = csrf();
        mvc.perform(put("/me/debt-cap").cookie(csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                        .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", "cap-anonymous")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/me/debt-cap").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/me/debt-cap").cookie(session).header("Origin", "http://127.0.0.1:8080")
                        .header("Idempotency-Key", "cap-first")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capCents\":0}"))
                .andExpect(status().isForbidden());
        for (String invalid : List.of("{}", "{\"capCents\":-100}", "{\"capCents\":150}", "{\"capCents\":1.0}",
                "{\"capCents\":2147483648}", "{\"capCents\":\"100\"}", "{\"capCents\":0,\"extra\":true}")) {
            mvc.perform(debtCapPut(session, csrf, "bad-cap", invalid)).andExpect(status().isBadRequest());
        }
        MvcResult saved = mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(saved.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isEqualTo(2147483600);
        UUID receipt = insertDebtReceipt(table, other, user, other, 1, "sent");
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":2147483600}"))
                .andExpect(status().isNoContent());
        mvc.perform(debtCapPut(session, csrf, "cap-first", "{\"capCents\":0}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isConflict());
        mvc.perform(debtCapPut(session, csrf, "cap-same", "{\"capCents\":2147483600}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        jdbc.update("UPDATE settlement_lines SET status = 'paid' WHERE id = ?", receipt);
        mvc.perform(debtCapPut(session, csrf, "cap-debt", "{\"capCents\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT cap_cents IS NULL FROM debt_caps WHERE user_id = ?", Boolean.class, user))
                .isTrue();
        mvc.perform(debtCapPut(session, csrf, "cap-zero", "{\"capCents\":0}")).andExpect(status().isNoContent());
        mvc.perform(invitePreferencesPut(session, csrf, "cap-zero", "{\"friends\":\"open\",\"following\":\"request\"}"))
                .andExpect(status().isConflict());
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        insertDebtReceipt(table, other, user, other, Long.MAX_VALUE, "unpaid");
        mvc.perform(debtCapPut(session, csrf, "cap-huge", "{\"capCents\":null}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                .isZero();
        Jwt validated = decoder.decode(session.getValue());
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCaps.replace(validated, "cap-first", 2147483600)).isEqualTo(DebtCapService.Outcome.UNAUTHORIZED);
    }

    @Test
    void debtCapUpdateSerializesWithDebtChangingUserLocks() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'caprace', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capracepeer', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Race', 'T', 'D8R4QA')", table);
        Jwt jwt = decoder.decode(loginAs("caprace").getValue());
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user);
                insertDebtReceipt(table, other, user, other, 100, "unpaid");
                changed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var update = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateSerializesWithInvitationAcceptance() throws Exception {
        UUID user = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID invite = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'capinvite', 1, ?, 'PLAYER', 'ACTIVE'), (?, 'capinvitehost', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, host, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Invite', 'T', 'D8R5QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host);
        jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", user, host);
        jdbc.update("INSERT INTO table_invites (id, table_id, from_id, user_id, status) VALUES (?, ?, ?, ?, 'PENDING')",
                invite, table, host, user);
        insertDebtBet(table, user, host, "PENDING", false, true, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("capinvite").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
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
            var accept = executor.submit(() -> invitationResponses.respond(jwt, invite, true, "invite-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            var cap = executor.submit(() -> {
                started.countDown();
                try {
                    return debtCaps.replace(jwt, "cap-invite-race", 100);
                } finally {
                    finished.countDown();
                }
            });
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(accept.get(10, TimeUnit.SECONDS)).isEqualTo(InvitationResponseService.Outcome.UPDATED);
            assertThatThrownBy(() -> cap.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(DebtCapService.OutstandingDebt.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_caps WHERE user_id = ?", Integer.class, user)).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void debtCapUpdateRollsBackIfSessionExpiresWhileWaiting() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'capexpiry', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", user);
        Jwt validated = decoder.decode(loginAs("capexpiry").getValue());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var blocker = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT user_id FROM debt_caps WHERE user_id = ? FOR UPDATE", UUID.class, user);
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
            var update = executor.submit(() -> debtCaps.replace(expiring, "cap-expiry", 200));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'INSERT INTO debt_caps (user_id, cap_cents)%'
                        )
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            Thread.sleep(Math.max(0, java.time.Duration.between(java.time.Instant.now(), expiry).toMillis()) + 100);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(MutationAuthentication.ExpiredSession.class);
            assertThat(jdbc.queryForObject("SELECT cap_cents FROM debt_caps WHERE user_id = ?", Integer.class, user))
                    .isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, user))
                    .isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void settingsReadsReturnOwnValuesAndRecheckCurrentSession() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'settingone', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'settingtwo', 1, ?, 'PLAYER', 'ACTIVE')
                """, user, hash, other, hash);
        mvc.perform(get("/me/debt-cap")).andExpect(status().isUnauthorized());
        Cookie session = loginAs("settingone");
        Cookie otherSession = loginAs("settingtwo");

        Csrf csrf = csrf();
        mvc.perform(get("/me/notification-preferences").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(get("/notifications").cookie(session)).andExpect(status().isNotFound());
        mvc.perform(post("/notifications/read-all").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.DELETE, "/notifications").cookie(session, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNotFound());

        MvcResult emptyDebt = mvc.perform(get("/me/debt").cookie(session)).andExpect(status().isOk()).andReturn();
        assertThat(emptyDebt.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.readTree(emptyDebt.getResponse().getContentAsString()).path("debtCents").asLong()).isZero();
        MvcResult defaultCap = mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(defaultCap.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode cap = json.readTree(defaultCap.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.has("capCents")).isTrue();
        assertThat(cap.path("capCents").isNull()).isTrue();
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0), (?, 321)", user, other);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").asInt()).isZero();
        JsonNode otherCap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(otherSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(otherCap.path("capCents").asInt()).isEqualTo(321);
        jdbc.update("UPDATE debt_caps SET cap_cents = 2147483647 WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("DebtCap").validate(cap)).isEmpty();
        assertThat(cap.path("capCents").asLong()).isEqualTo(2147483647L);
        jdbc.update("UPDATE debt_caps SET cap_cents = NULL WHERE user_id = ?", user);
        cap = json.readTree(mvc.perform(get("/me/debt-cap").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cap.path("capCents").isNull()).isTrue();

        Jwt validatedBeforeFreeze = decoder.decode(session.getValue());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", user);
        assertThat(debtCapController.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        assertThat(debtCapController.debt(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
        mvc.perform(get("/me/debt-cap").cookie(session)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", user);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", user);
        assertThat(debtCapController.debtCap(validatedBeforeFreeze).getStatusCode().value()).isEqualTo(401);
    }
}
