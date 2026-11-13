package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.settlement.TablePointsSettlementService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class BetIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private BetLockService betLocks;

    @Autowired
    private BetPickService betPicks;

    @Autowired
    private TablePointsSettlementService pointsSettlement;

    @Autowired
    private U2PickProposalService u2PickProposals;

    @Autowired
    private U2PickResolutionService u2PickResolution;

    @Test
    void betReadsProtectActivePicksAndRevealLockedBetsForBothModeFamilies() throws Exception {
        UUID host = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID watcher = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID emptyTable = UUID.randomUUID();
        UUID customBet = UUID.randomUUID();
        UUID sportsBet = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betreadhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadmember', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadwatcher', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadoutside', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betreadadmin', 1, ?, 'ADMIN', 'ACTIVE')
                """, host, hash, member, hash, watcher, hash, outsider, hash, admin, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Bet reads', 'T', 'Z9R6QA'), (?, 'Empty bets', 'T', 'Z9R5QA')
                """, table, emptyTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'HOST')
                """, table, host, table, member, table, watcher, emptyTable, host);
        jdbc.update("""
                INSERT INTO bets (id, table_id, proposer_id, mode, game_id, config, question, choices,
                                  wager_cents, is_4fun, locks_at, winning_choice, called_by)
                VALUES (?, ?, ?, 'u2pick', NULL, '{}'::jsonb, 'Custom?', '["Yes","No"]'::jsonb,
                        100, false, now() + interval '1 minute', 'Yes', ?),
                       (?, ?, ?, 'either_or', 'game-1', '{}'::jsonb, 'Sports?', '["Pass","Run"]'::jsonb,
                        100, false, now() + interval '1 minute', NULL, NULL)
                """, customBet, table, host, host, sportsBet, table, host);
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?), (?, ?)
                """, customBet, host, customBet, member, customBet, watcher, sportsBet, member);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No'), (?, ?, 'Pass')
                """, customBet, member, customBet, watcher, sportsBet, member);
        Cookie hostSession = loginAs("betreadhost");
        Cookie memberSession = loginAs("betreadmember");
        Cookie outsideSession = loginAs("betreadoutside");
        Cookie adminSession = loginAs("betreadadmin");

        mvc.perform(get("/tables/" + table + "/bets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/tables/" + table + "/bets").cookie(outsideSession)).andExpect(status().isNotFound());
        mvc.perform(get("/bets/" + customBet).cookie(outsideSession)).andExpect(status().isNotFound());
        mvc.perform(get("/bets/" + customBet).cookie(adminSession)).andExpect(status().isForbidden());
        MvcResult empty = mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(empty.getResponse().getContentAsString())).isEmpty();
        MvcResult list = mvc.perform(get("/tables/" + table + "/bets").cookie(memberSession))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode bets = json.readTree(list.getResponse().getContentAsString());
        assertThat(bets.size()).isEqualTo(2);
        for (JsonNode bet : bets) {
            assertThat(OpenApiSchemas.load("Bet").validate(bet)).isEmpty();
            assertThat(bet.has("picks")).isFalse();
            assertThat(bet.has("noGuessUserIds")).isFalse();
            assertThat(bet.has("calledBy")).isFalse();
            assertThat(bet.has("winningChoice")).isFalse();
            assertThat(bet.path("myParticipation").asText()).isEqualTo("picked");
            assertThat(bet.path("myPick").asText())
                    .isEqualTo(bet.path("id").asText().equals(customBet.toString()) ? "Yes" : "Pass");
        }
        JsonNode hostActive = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hostActive.path("myParticipation").asText()).isEqualTo("no_guess");
        assertThat(hostActive.path("myPick").isNull()).isTrue();
        assertThat(hostActive.path("pickCount").asInt()).isEqualTo(2);

        jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = now(), remainder_rule = 'host',
                                remainder_host_id = ? WHERE id IN (?, ?)
                """, host, customBet, sportsBet);
        JsonNode locked = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(locked)).isEmpty();
        assertThat(locked.path("picks").size()).isEqualTo(2);
        assertThat(locked.path("noGuessUserIds").size()).isEqualTo(1);
        assertThat(locked.path("noGuessUserIds").get(0).asText()).isEqualTo(host.toString());
        assertThat(locked.has("winningChoice")).isFalse();
        assertThat(locked.path("remainderHostId").asText()).isEqualTo(host.toString());
        JsonNode lockedSports = json.readTree(mvc.perform(get("/bets/" + sportsBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(lockedSports)).isEmpty();
        assertThat(lockedSports.path("myParticipation").asText()).isEqualTo("not_joined");
        assertThat(lockedSports.path("picks").get(0).path("choice").asText()).isEqualTo("Pass");

        jdbc.update("UPDATE bets SET status = 'WASHED', closed_at = now(), wash_reason = 'No winner' WHERE id = ?",
                sportsBet);
        JsonNode washed = json.readTree(mvc.perform(get("/bets/" + sportsBet).cookie(hostSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(washed)).isEmpty();
        assertThat(washed.path("washReason").asText()).isEqualTo("No winner");
        assertThat(washed.path("deltasCents").isEmpty()).isTrue();

        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.update("UPDATE bets SET status = 'SETTLED', closed_at = now() WHERE id = ?", customBet);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents) VALUES
                    (?, ?, ?, ?, 100), (?, ?, ?, ?, -100)
                    """, UUID.randomUUID(), table, customBet, member,
                    UUID.randomUUID(), table, customBet, watcher);
        });
        JsonNode resolved = json.readTree(mvc.perform(get("/bets/" + customBet).cookie(memberSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(resolved)).isEmpty();
        assertThat(resolved.path("winningChoice").asText()).isEqualTo("Yes");
        assertThat(resolved.path("deltasCents").path(member.toString()).asLong()).isEqualTo(100);
        assertThat(resolved.path("deltasCents").path(watcher.toString()).asLong()).isEqualTo(-100);
        jdbc.update("DELETE FROM table_members WHERE table_id = ? AND user_id = ?", table, member);
        mvc.perform(get("/bets/" + customBet).cookie(memberSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table + "/bets").cookie(memberSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE tables SET archived_at = now() WHERE id = ?", table);
        mvc.perform(get("/bets/" + customBet).cookie(hostSession)).andExpect(status().isNotFound());
        mvc.perform(get("/tables/" + table + "/bets").cookie(hostSession)).andExpect(status().isNotFound());
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", host);
        mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", host);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", host);
        mvc.perform(get("/tables/" + emptyTable + "/bets").cookie(hostSession)).andExpect(status().isUnauthorized());
    }

    @Test
    void internalU2PickLifecycleMovesFromProposalToLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'flowhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'flowplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'U2Pick flow', 'T', 'Y8S3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie hostCookie = loginAs("flowhost");
        Cookie playerCookie = loginAs("flowplayer");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt playerJwt = decoder.decode(playerCookie.getValue());
        var proposal = new U2PickProposalService.Proposal(new U2PickDefinition("Who?", List.of("Yes", "No")),
                json.createObjectNode(), 125, 30, false, U2PickSettler.Rule.HOSTS);
        UUID bet = u2PickProposals.create(hostJwt, table, proposal, "flow-propose").betId();
        assertThat(bet).isNotNull();
        assertThat(betPicks.change(hostJwt, bet, "Yes", "flow-host-pick")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(betPicks.change(playerJwt, bet, "No", "flow-player-pick")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Yes", "flow-too-early"))
                .isInstanceOf(U2PickResolutionService.Closed.class);
        JsonNode active = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(active.path("myPick").asText()).isEqualTo("Yes");
        assertThat(active.path("pickCount").asInt()).isEqualTo(2);
        assertThat(active.has("picks")).isFalse();
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        JsonNode pending = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        assertThat(pending.path("picks").size()).isEqualTo(2);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "flow-result"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        JsonNode result = json.readTree(mvc.perform(get("/bets/" + bet).cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("status").asText()).isEqualTo("SETTLED");
        assertThat(result.path("pointsSettled").asBoolean()).isFalse();
        assertThat(result.path("deltasCents").path(host.toString()).asLong()).isEqualTo(125);
        assertThat(result.path("deltasCents").path(player.toString()).asLong()).isEqualTo(-125);
        JsonNode debt = json.readTree(mvc.perform(get("/me/debt").cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(125);
        var settlement = pointsSettlement.settle(hostJwt, table, "flow-points");
        assertThat(settlement.outcome()).isEqualTo(TablePointsSettlementService.Outcome.CREATED);
        assertThat(jdbc.queryForObject("""
                SELECT amount_cents FROM settlement_lines WHERE settlement_id = ? AND payer_id = ? AND payee_id = ?
                """, Long.class, settlement.settlementId(), player, host)).isEqualTo(125);
        JsonNode settled = json.readTree(mvc.perform(get("/bets/" + bet).cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(settled.path("pointsSettled").asBoolean()).isTrue();
        debt = json.readTree(mvc.perform(get("/me/debt").cookie(playerCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(debt.path("debtCents").asLong()).isEqualTo(125);
    }

    @Test
    void gatedBetHttpAdaptersValidateAndRunTheU2PickLifecycle() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'betapihost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betapiplayer', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'betapioutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bet HTTP', 'T', 'H4B3QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        Cookie hostCookie = loginAs("betapihost");
        Cookie playerCookie = loginAs("betapiplayer");
        Cookie outsideCookie = loginAs("betapioutside");
        Csrf csrf = csrf();
        String proposal = """
                {"modeId":"u2pick","config":{"question":"outdated","options":["old"],"tag":"kept"},
                 "question":"  Who wins?  ","choices":[" Yes ","No"],"wagerCents":125,
                 "durationSec":30,"forFun":false,"settledBy":"hosts"}
                """;
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie)
                        .header("Origin", "http://127.0.0.1:8080").header("Idempotency-Key", "http-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(proposal)).andExpect(status().isForbidden());
        mvc.perform(post("/tables/" + table + "/bets").cookie(csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-anonymous").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/tables/" + table + "/bets").cookie(outsideCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-outside").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isNotFound());
        for (String invalid : List.of(
                proposal.replace("\"u2pick\"", "\"either_or\""),
                proposal.replace("\"durationSec\":30", "\"durationSec\":29"),
                proposal.replace("\"wagerCents\":125", "\"wagerCents\":125.0"),
                proposal.replace("\"choices\":[\" Yes \",\"No\"]", "\"choices\":[\"Yes\",\" yes \"]"),
                proposal.replace("\"tag\":\"kept\"", "\"tag\":true"),
                proposal.replace("\"forFun\":false", "\"forFun\":false,\"unexpected\":1"))) {
            mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "http-invalid").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        MvcResult created = mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isCreated()).andReturn();
        assertThat(created.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode betView = json.readTree(created.getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(betView)).isEmpty();
        assertThat(betView.path("question").asText()).isEqualTo("Who wins?");
        assertThat(betView.path("config").path("question").asText()).isEqualTo("Who wins?");
        assertThat(betView.path("config").path("options")).isEqualTo(betView.path("choices"));
        UUID bet = UUID.fromString(betView.path("id").asText());
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON).content(proposal))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(json.readTree(result.getResponse().getContentAsString()).path("id").asText())
                        .isEqualTo(bet.toString()));
        mvc.perform(post("/tables/" + table + "/bets").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-proposal").contentType(MediaType.APPLICATION_JSON)
                        .content(proposal.replace("Who wins?", "Who loses?"))).andExpect(status().isConflict());
        mvc.perform(delete("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-clear-before")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Integer.class, bet, player)).isZero();
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-invalid-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Maybe\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-host-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        mvc.perform(delete("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-clear-after")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Integer.class, bet, player)).isEqualTo(1);
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-repick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        JsonNode active = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(active.has("picks")).isFalse();
        assertThat(active.path("pickCount").asInt()).isEqualTo(2);
        mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-early-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isConflict());
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-late-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isConflict());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"No\"}")).andExpect(status().isNoContent());
        mvc.perform(put("/bets/" + bet + "/pick").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-player-pick").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"Yes\"}")).andExpect(status().isConflict());
        mvc.perform(post("/bets/" + bet + "/settle").cookie(playerCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-not-settler").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Typo\"}")).andExpect(status().isBadRequest());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/bets/" + bet + "/settle").cookie(hostCookie, csrf.cookie())
                            .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                            .header("Idempotency-Key", "http-result").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet)).isZero();
        MvcResult points = mvc.perform(post("/tables/" + table + "/settle-points").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "http-points")).andExpect(status().isCreated()).andReturn();
        JsonNode pointLines = json.readTree(points.getResponse().getContentAsString()).path("lines");
        assertThat(pointLines.size()).isEqualTo(1);
        UUID receipt = UUID.fromString(pointLines.get(0).path("id").asText());
        assertThat(groupHttpAction(playerCookie, csrf, host, "sent", groupRevision(playerCookie), "http-group-sent")
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(receiptHttpAction(hostCookie, csrf, receipt, "confirm", 1, "http-receipt-confirm")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_lines WHERE id = ?", String.class, receipt))
                .isEqualTo("paid");
    }

    @Test
    void u2PickResolutionPostsZeroSumAndHonorsRetries() throws Exception {
        UUID host = UUID.randomUUID();
        UUID winner1 = UUID.randomUUID();
        UUID winner2 = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resolvehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin1', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolvewin2', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveloser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resolveoutside', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, winner1, hash, winner2, hash, loser, hash, outsider, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution', 'T', 'Y8R5QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, winner1, table, winner2, table, loser);
        UUID bet = insertDebtBet(table, winner1, host, "PENDING", false, true, false, 101, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants', remainder_rule = 'host', remainder_host_id = ? WHERE id = ?",
                host, bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", bet, winner2, bet, loser);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes'), (?, ?, 'No')",
                bet, winner2, bet, loser);
        Cookie hostCookie = loginAs("resolvehost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt winnerJwt = decoder.decode(loginAs("resolvewin1").getValue());
        Jwt outsiderJwt = decoder.decode(loginAs("resolveoutside").getValue());
        assertThat(u2PickResolution.resolve(winnerJwt, bet, "Yes", "resolve-winner"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(outsiderJwt, bet, "Yes", "resolve-outsider"))
                .isEqualTo(U2PickResolutionService.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Typo", "resolve-key"))
                .isInstanceOf(U2PickResolutionService.InvalidChoice.class);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.REPLAYED);
        assertThat(u2PickResolution.resolve(hostJwt, bet, "No", "resolve-key"))
                .isEqualTo(U2PickResolutionService.Outcome.CONFLICT);
        assertThatThrownBy(() -> u2PickResolution.resolve(hostJwt, bet, "Yes", "resolve-again"))
                .isInstanceOf(U2PickResolutionService.Closed.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, host)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, winner1)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT delta_cents FROM ledger_entries WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, loser)).isEqualTo(-101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'",
                Integer.class, bet.toString())).isEqualTo(1);
        JsonNode view = json.readTree(mvc.perform(get("/bets/" + bet).cookie(hostCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(view)).isEmpty();
        assertThat(view.path("status").asText()).isEqualTo("SETTLED");
        assertThat(view.path("calledBy").asText()).isEqualTo(host.toString());
        assertThat(view.path("winningChoice").asText()).isEqualTo("Yes");
        assertThat(view.path("pointsSettled").asBoolean()).isFalse();
    }

    @Test
    void u2PickResolutionHandlesWashesFourFunAndFrozenFallback() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resultfunhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfunuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resultfrozen', 1, ?, 'PLAYER', 'FROZEN')
                """, host, hash, player, hash, frozen, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Fun result', 'T', 'Y8R6QA')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, player, table, frozen);
        UUID fun = insertDebtBet(table, host, host, "PENDING", true, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'non_participants' WHERE id = ?", fun);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", fun, player, fun, frozen);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", fun, player);
        Cookie hostCookie = loginAs("resultfunhost");
        Jwt hostJwt = decoder.decode(hostCookie.getValue());
        Jwt playerJwt = decoder.decode(loginAs("resultfunuser").getValue());
        assertThat(u2PickResolution.resolve(playerJwt, fun, "Yes", "fun-nonhost"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThat(u2PickResolution.resolve(hostJwt, fun, "Yes", "fun-host"))
                .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, fun)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, fun)).isZero();
        UUID washed = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'me' WHERE id = ?", washed);
        assertThat(u2PickResolution.resolve(playerJwt, washed, null, "null-nonproposer"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        Csrf csrf = csrf();
        mvc.perform(post("/bets/" + washed + "/settle").cookie(hostCookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "null-result").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, washed)).isEqualTo("WASHED");
        assertThat(jdbc.queryForObject("SELECT wash_reason FROM bets WHERE id = ?", String.class, washed))
                .isEqualTo("Outcome fell outside the available choices");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, washed)).isZero();
        UUID sports = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", sports);
        assertThat(u2PickResolution.resolve(hostJwt, sports, "Yes", "sports-manual"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
    }

    @Test
    void u2PickResolutionRejectsPreexistingPendingLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resbadhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resbadplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Bad ledger', 'T', 'Y8S4QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, -100);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resbadhost").getValue());
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-existing"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already has ledger entries");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                """, Integer.class, bet.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE user_id = ?", Integer.class, host))
                .isZero();
    }

    @Test
    void u2PickResolutionRetriesWhenMembershipChangesWhileAcquiringLocks() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID joining = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resraceuser', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resracejoin', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash, joining, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution race', 'T', 'Y8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("resracehost").getValue());
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, joining);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, joining);
                inserted.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var resolution = executor.submit(() -> u2PickResolution.resolve(jwt, bet, "Yes", "res-race"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> resolution.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(U2PickResolutionService.MembersChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");
            assertThat(u2PickResolution.resolve(jwt, bet, "Yes", "res-race"))
                    .isEqualTo(U2PickResolutionService.Outcome.RESOLVED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousU2PickResolutionsPostOnlyOneLedger() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'resduelhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'resdueluser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Resolution duel', 'T', 'Y8R8QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, host, host, "PENDING", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'anyone' WHERE id = ?", bet);
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No')", bet, player);
        Jwt hostJwt = decoder.decode(loginAs("resduelhost").getValue());
        Jwt playerJwt = decoder.decode(loginAs("resdueluser").getValue());
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(hostJwt, bet, "Yes", "res-duel-host").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                try {
                    return u2PickResolution.resolve(playerJwt, bet, "No", "res-duel-player").name();
                } catch (U2PickResolutionService.Closed ignored) {
                    return "CLOSED";
                }
            });
            start.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESOLVED", "CLOSED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, bet))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(delta_cents) FROM ledger_entries WHERE bet_id = ?", Long.class, bet))
                    .isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.resolved.v1'
                    """, Integer.class, bet.toString())).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void u2PickResolutionFailsClosedWhenCopiesNeedPropagation() throws Exception {
        UUID host = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        UUID copyTable = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'rescopyhost', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, passwords.encode("a secure test password"));
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code) VALUES
                (?, 'Source', 'T', 'Y8R9QA'), (?, 'Copy', 'T', 'Y8S2QA')
                """, table, copyTable);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'HOST')",
                table, host, copyTable, host);
        UUID source = insertDebtBet(table, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET settler_rule = 'hosts' WHERE id = ?", source);
        UUID copy = insertDebtBet(copyTable, host, host, "PENDING", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET source_bet_id = ?, source_table_id = ? WHERE id = ?", source, table, copy);
        Cookie cookie = loginAs("rescopyhost");
        Jwt jwt = decoder.decode(cookie.getValue());
        assertThat(u2PickResolution.resolve(jwt, copy, "Yes", "res-copy"))
                .isEqualTo(U2PickResolutionService.Outcome.FORBIDDEN);
        assertThatThrownBy(() -> u2PickResolution.resolve(jwt, source, "Yes", "res-source"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("propagation");
        Csrf csrf = csrf();
        MvcResult blocked = mvc.perform(post("/bets/" + source + "/settle").cookie(cookie, csrf.cookie())
                        .header("Origin", "http://127.0.0.1:8080").header("X-CSRF-TOKEN", csrf.token())
                        .header("Idempotency-Key", "res-http-source").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"winningChoice\":\"Yes\"}")).andExpect(status().isConflict()).andReturn();
        assertThat(json.readTree(blocked.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("quick_pick_propagation_unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, source)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bet_id = ?", Integer.class, source)).isZero();
    }

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

    @Test
    void betPickPreservesParticipationAndRetryPrivacy() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickplayer', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Pick', 'T', 'T8R5QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '10 minutes' WHERE id = ?", bet);
        Jwt jwt = decoder.decode(loginAs("pickplayer").getValue());
        assertThat(betPicks.change(jwt, bet, null, "pick-clear-before")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Other", "pick-invalid"))
                .isInstanceOf(BetPickService.InvalidChoice.class);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.UPDATED);
        Long order = jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-invalid")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                String.class, bet, player)).isEqualTo("No");
        assertThat(betPicks.change(jwt, bet, "No", "pick-invalid")).isEqualTo(BetPickService.Outcome.CONFLICT);
        assertThat(betPicks.change(jwt, bet, null, "pick-clear")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UPDATED);
        assertThat(jdbc.queryForObject("SELECT participation_order FROM bet_participants WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, player)).isEqualTo(order);
        List<String> payloads = jdbc.queryForList("""
                SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.pick_count.changed.v1'
                """, String.class, bet.toString());
        assertThat(payloads).hasSize(3);
        for (String payload : payloads) {
            JsonNode event = json.readTree(payload);
            assertThat(event.size()).isEqualTo(2);
            assertThat(event.path("betId").asText()).isEqualTo(bet.toString());
            assertThat(event.path("tableId").asText()).isEqualTo(table.toString());
        }
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", bet);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(betPicks.change(jwt, bet, "No", "pick-change")).isEqualTo(BetPickService.Outcome.REPLAYED);
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "No", "pick-after-lock"))
                .isInstanceOf(BetPickService.Closed.class);
        jdbc.update("UPDATE table_members SET can_bet = false WHERE table_id = ? AND user_id = ?", table, player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.FORBIDDEN);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-rejoin")).isEqualTo(BetPickService.Outcome.UNAUTHORIZED);
    }

    @Test
    void betPicksUseSharedCapAndExemptFourFun() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickcaphost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickcapuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick cap', 'T', 'T8R6QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 99)", player);
        insertDebtBet(table, player, host, "PENDING", false, true, false, 50, 0);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 50, 0);
        UUID fun = insertDebtBet(table, player, host, "ACTIVE", true, false, false, 50, 0);
        jdbc.update("""
                UPDATE bets SET locks_at = now() + interval '10 minutes',
                    mode = 'either_or', game_id = 'game-1' WHERE id IN (?, ?)
                """, bet, fun);
        Jwt jwt = decoder.decode(loginAs("pickcapuser").getValue());
        assertThatThrownBy(() -> betPicks.change(jwt, bet, "Yes", "pick-cap"))
                .isInstanceOf(BetPickService.DebtCapExceeded.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
        assertThat(betPicks.change(jwt, fun, "Yes", "pick-fun")).isEqualTo(BetPickService.Outcome.UPDATED);
        jdbc.update("UPDATE debt_caps SET cap_cents = 100 WHERE user_id = ?", player);
        assertThat(betPicks.change(jwt, bet, "Yes", "pick-cap")).isEqualTo(BetPickService.Outcome.UPDATED);
    }

    @Test
    void betPickRechecksDeadlineAfterWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'pickwaithost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'pickwaituser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Pick wait', 'T', 'T8R7QA')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        Jwt jwt = decoder.decode(loginAs("pickwaituser").getValue());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                held.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '4 seconds' WHERE id = ?", bet);
            var pick = executor.submit(() -> betPicks.change(jwt, bet, "Yes", "pick-wait"));
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < timeout) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                            AND query LIKE 'SELECT id FROM tables WHERE id = %' AND query LIKE '%archived_at%')
                        """, Boolean.class));
                if (!waiting) Thread.sleep(25);
            }
            assertThat(waiting).isTrue();
            assertThat(jdbc.queryForObject("SELECT clock_timestamp() < locks_at FROM bets WHERE id = ?",
                    Boolean.class, bet)).isTrue();
            boolean due = false;
            while (!due && System.nanoTime() < timeout) {
                due = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT clock_timestamp() >= locks_at FROM bets WHERE id = ?", Boolean.class, bet));
                if (!due) Thread.sleep(25);
            }
            assertThat(due).isTrue();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> pick.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BetPickService.Closed.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet)).isZero();
            jdbc.update("UPDATE bets SET locks_at = clock_timestamp() + interval '1 minute' WHERE id = ?", bet);
            assertThat(betPicks.change(jwt, bet, "Yes", "pick-wait")).isEqualTo(BetPickService.Outcome.UPDATED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betLockSnapshotsAndRechecksEligibilityWithoutLosingParticipation() {
        UUID host = UUID.randomUUID();
        UUID exact = UUID.randomUUID();
        UUID over = UUID.randomUUID();
        UUID frozen = UUID.randomUUID();
        UUID limited = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockexact', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockover', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockfrozen', 1, ?, 'PLAYER', 'FROZEN'),
                (?, 'locklimited', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, exact, hash, over, hash, frozen, hash, limited, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Lock', 'T', 'V8R5QA', 'host')", table);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role, can_bet) VALUES
                (?, ?, 'HOST', true), (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', true),
                (?, ?, 'MEMBER', true), (?, ?, 'MEMBER', false)
                """, table, host, table, exact, table, over, table, frozen, table, limited);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100), (?, 99)", exact, over);
        UUID bet = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("""
                INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?), (?, ?)
                """, bet, over, bet, frozen, bet, limited);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'No'), (?, ?, 'No'), (?, ?, 'No')
                """, bet, over, bet, frozen, bet, limited);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForList("SELECT user_id FROM picks WHERE bet_id = ?", UUID.class, bet))
                .containsExactly(exact);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id = ?", Integer.class, bet))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT remainder_host_id FROM bets WHERE id = ?", UUID.class, bet))
                .isEqualTo(host);
        assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.ALREADY_LOCKED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'
                """, Integer.class, bet.toString())).isEqualTo(1);

        UUID fun = insertDebtBet(table, over, host, "ACTIVE", true, true, false, 100, 0);
        assertThat(betLocks.lock(fun)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, fun)).isEqualTo(1);
        UUID future = insertDebtBet(table, exact, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET locks_at = now() + interval '1 minute' WHERE id = ?", future);
        assertThat(betLocks.lock(future)).isEqualTo(BetLockService.Outcome.NOT_DUE);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, future)).isEqualTo("ACTIVE");
    }

    @Test
    void betLockRollsBackWhenHostRemainderHasNoHost() {
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, 'locknohost', 1, ?, 'PLAYER', 'ACTIVE')
                """, player, passwords.encode("a secure test password"));
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'No host', 'T', 'V8R6QA', 'host')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 0)", player);
        UUID bet = insertDebtBet(table, player, player, "ACTIVE", false, true, false, 100, 0);
        assertThatThrownBy(() -> betLocks.lock(bet)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'bet.locked.v1'",
                Integer.class, bet.toString())).isZero();
    }

    @Test
    void simultaneousBetLocksSerializeSharedDebtExposure() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID firstTable = UUID.randomUUID();
        UUID secondTable = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'lockracehost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'lockraceuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES
                (?, 'First', 'T', 'V8R7QA', 'earliest'), (?, 'Second', 'T', 'V8R8QA', 'earliest')
                """, firstTable, secondTable);
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES
                (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'HOST'), (?, ?, 'MEMBER')
                """, firstTable, host, firstTable, player, secondTable, host, secondTable, player);
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 100)", player);
        UUID first = insertDebtBet(firstTable, player, host, "ACTIVE", false, true, false, 100, 0);
        UUID second = insertDebtBet(secondTable, player, host, "ACTIVE", false, true, false, 100, 0);
        jdbc.update("UPDATE bets SET mode = 'either_or', game_id = 'game-1' WHERE id = ?", second);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(first);
            });
            var two = executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                return betLocks.lock(second);
            });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bet_participants WHERE bet_id IN (?, ?)",
                    Integer.class, first, second)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void betLockRetriesWhenPickAppearsWhileWaitingForTable() throws Exception {
        UUID host = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID table = UUID.randomUUID();
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, 'locknewhost', 1, ?, 'PLAYER', 'ACTIVE'),
                (?, 'locknewuser', 1, ?, 'PLAYER', 'ACTIVE')
                """, host, hash, player, hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code, remainder_rule) VALUES (?, 'Race', 'T', 'V8R9QA', 'earliest')", table);
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER')",
                table, host, table, player);
        UUID bet = insertDebtBet(table, player, host, "ACTIVE", false, false, false, 100, 0);
        var inserted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, player);
                jdbc.queryForObject("SELECT id FROM tables WHERE id = ? FOR UPDATE", UUID.class, table);
                jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", bet, player);
                jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Yes')", bet, player);
                inserted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var lock = executor.submit(() -> betLocks.lock(bet));
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
            assertThatThrownBy(() -> lock.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BetLockService.ParticipantsChanged.class);
            assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("ACTIVE");
            assertThat(betLocks.lock(bet)).isEqualTo(BetLockService.Outcome.LOCKED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM picks WHERE bet_id = ?", Integer.class, bet)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
