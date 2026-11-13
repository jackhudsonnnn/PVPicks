package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import com.p2picks.app.settlement.TablePointsSettlementService;
import com.p2picks.app.bets.U2PickSettler;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class BetLifecycleIntegrationTest extends IntegrationTestSupport {
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
    void betHttpAdaptersValidateAndRunTheU2PickLifecycle() throws Exception {
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
}
