package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.bets.U2PickSettler;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

class BetProposalIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private U2PickProposalService u2PickProposals;

    @Test
    void u2PickProposalPersistsNormalizedDefinitionAndSafeRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'prophost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Proposal', 'T', 'T8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie cookie = loginAs("propplayer");
        Jwt jwt = decoder.decode(cookie.getValue());
        var config = json.createObjectNode().put("z", 2).put("a", "metadata").put("question", "Conflicting?");
        config.putArray("options").add("Wrong").add("Values");
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition(" Question? ", List.of(" Yes ", "No")),
                config, 100, 45, false, null);
        var created = u2PickProposals.create(jwt, table, proposal, "proposal-key");
        assertThat(created.outcome()).isEqualTo(U2PickProposalService.Outcome.CREATED);
        UUID bet = created.betId();
        assertThat(bet).isNotNull();
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (locks_at - created_at)) FROM bets WHERE id = ?",
                Long.class, bet)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.created.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);
        JsonNode view = json.readTree(mvc.perform(get("/bets/" + bet).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(view)).isEmpty();
        assertThat(view.path("question").asText()).isEqualTo("Question?");
        assertThat(view.path("choices").get(0).asText()).isEqualTo("Yes");
        assertThat(view.path("config").path("question").asText()).isEqualTo("Question?");
        assertThat(view.path("config").path("options")).isEqualTo(view.path("choices"));
        assertThat(view.path("config").path("a").asText()).isEqualTo("metadata");
        assertThat(view.path("settledBy").asText()).isEqualTo("hosts");
        assertThat(view.path("myParticipation").asText()).isEqualTo("not_joined");
        var reordered = new U2PickProposalService.Proposal(new U2PickDefinition("Question?", List.of("Yes", "No")),
                json.createObjectNode().put("a", "metadata").put("z", 2), 100, 45, false,
                U2PickSettler.Rule.HOSTS);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key"))
                .isEqualTo(new U2PickProposalService.Result(U2PickProposalService.Outcome.REPLAYED, bet));
        var changed = new U2PickProposalService.Proposal(reordered.definition(), reordered.config(), 100, 60,
                false, U2PickSettler.Rule.HOSTS);
        assertThat(u2PickProposals.create(jwt, table, changed, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.created.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);
        jdbc.update("UPDATE tables SET u2pick_enabled = false, hosts_only_bets = true WHERE id = ?", table);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.REPLAYED);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-denied").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.NOT_FOUND);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", player);
        assertThat(u2PickProposals.create(jwt, table, reordered, "proposal-key").outcome())
                .isEqualTo(U2PickProposalService.Outcome.UNAUTHORIZED);
    }

    @Test
    void u2PickProposalChecksCapPermissionsAndInput() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'propcaphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propcapuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, hosts_only_bets)
                VALUES (?, 'Proposal cap', 'T', 'T8R9QA', true)
                """, table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 99)", player);
        Jwt jwt = decoder.decode(loginAs("propcapuser").getValue());
        U2PickDefinition definition = new U2PickDefinition("Question?", List.of("Yes", "No"));
        var proposal = new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 30,
                false, U2PickSettler.Rule.ME);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-cap").outcome())
                .isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE tables SET hosts_only_bets = false WHERE id = ?", table);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, proposal, "proposal-cap"))
                .isInstanceOf(U2PickProposalService.DebtCapExceeded.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE table_id = ?", Integer.class, table)).isZero();
        var fun = new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 600,
                true, U2PickSettler.Rule.ANYONE);
        assertThat(u2PickProposals.create(jwt, table, fun, "proposal-fun").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", player);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-cap").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
        assertThatThrownBy(() -> new U2PickProposalService.Proposal(definition, json.createObjectNode(), 9, 30,
                false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new U2PickProposalService.Proposal(definition, json.createObjectNode(), 100, 601,
                false, null)).isInstanceOf(IllegalArgumentException.class);
        var invalidConfig = new U2PickProposalService.Proposal(definition, json.createObjectNode().put("bad", true),
                100, 30, false, null);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, invalidConfig, "proposal-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        var invalidText = new U2PickProposalService.Proposal(definition,
                json.createObjectNode().put("note", "\u0000"), 100, 30, false, null);
        assertThatThrownBy(() -> u2PickProposals.create(jwt, table, invalidText, "proposal-invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-invalid").outcome())
                .isEqualTo(U2PickProposalService.Outcome.CREATED);
    }

    @Test
    void u2PickProposalRechecksTableSettingsAfterWaiting() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'propwaithost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'propwaituser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Proposal wait', 'T', 'T8S2QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Jwt jwt = decoder.decode(loginAs("propwaituser").getValue());
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition("Question?", List.of("Yes", "No")),
                json.createObjectNode(), 100, 30, false, U2PickSettler.Rule.HOSTS);
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, host);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("UPDATE tables SET u2pick_enabled = false WHERE id = ?", table);
                changed.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
            var request = executor.submit(() -> u2PickProposals.create(jwt, table, proposal, "proposal-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT hosts_only_bets, u2pick_enabled FROM tables%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(request.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(U2PickProposalService.Outcome.FORBIDDEN);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bets WHERE table_id = ?", Integer.class, table)).isZero();
            jdbc.update("UPDATE tables SET u2pick_enabled = true WHERE id = ?", table);
            assertThat(u2PickProposals.create(jwt, table, proposal, "proposal-race").outcome())
                    .isEqualTo(U2PickProposalService.Outcome.CREATED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
