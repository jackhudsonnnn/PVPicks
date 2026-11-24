package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SportsBetIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private BetLockService locks;

    @Autowired
    private SportsResolutionService resolutions;

    private final UUID host = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID table = UUID.randomUUID();
    private final String providerId = Long.toString(ThreadLocalRandom.current().nextLong(1L << 40));
    private final String gameId = "NBA-" + providerId;

    private void seed(String prefix, Instant startsAt) {
        String hash = passwords.encode("a secure test password");
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status) VALUES
                (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE'), (?, ?, 1, ?, 'PLAYER', 'ACTIVE')
                """, host, prefix + "h", hash, alice, prefix + "a", hash, bob, prefix + "b", hash);
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Sports', 'T', ?)", table, code());
        jdbc.update("""
                INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, table, host, table, alice, table, bob);
        jdbc.update("""
                INSERT INTO games (id, league, provider_id, status, starts_at, home_abbreviation, home_name, home_city,
                                   away_abbreviation, away_name, away_city, observed_at)
                VALUES (?, 'NBA', ?, 'SCHEDULED', ?, 'BOS', 'Celtics', 'Boston', 'NYK', 'Knicks', 'New York', now())
                """, gameId, providerId, java.sql.Timestamp.from(startsAt));
    }

    private static String code() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) code.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return code.toString();
    }

    private MockHttpServletRequestBuilder send(MockHttpServletRequestBuilder request, Cookie session, Csrf csrf,
            String body) {
        return request.cookie(session, csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String proposal(String mode, String line) {
        return """
                {"modeId":"%s","gameId":"%s","config":{"line":%s},"includesOvertime":true,
                 "wagerCents":100,"durationSec":600,"forFun":false}""".formatted(mode, gameId, line);
    }

    private void finishGame(int home, int away, Instant finalAt) {
        jdbc.update("""
                UPDATE games SET status = 'FINAL', provider_state = 'final', home_score = ?, away_score = ?,
                                 final_at = ? WHERE id = ?
                """, home, away, java.sql.Timestamp.from(finalAt), gameId);
    }

    private long delta(UUID bet, UUID user) {
        Long total = jdbc.queryForObject("SELECT COALESCE(sum(delta_cents), 0) FROM ledger_entries WHERE bet_id = ? "
                + "AND user_id = ?", Long.class, bet, user);
        return total == null ? 0 : total;
    }

    @Test
    void scoreBetsLockAtTheStartSettleFromTheFeedAndHostsCanOverride() throws Exception {
        Instant start = Instant.now().plusSeconds(120);
        seed("sp", start);
        Cookie hostSession = loginAs("sph");
        Cookie aliceSession = loginAs("spa");
        Cookie bobSession = loginAs("spb");
        Csrf csrf = csrf();

        mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf, proposal("total_disaster", "224.25")))
                .andExpect(status().isBadRequest());
        JsonNode bet = json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf,
                proposal("total_disaster", "224.5"))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(bet)).isEmpty();
        assertThat(bet.path("question").asText()).isEqualTo("NYK + BOS combined: over or under 224.5?");
        assertThat(bet.path("choices").toString()).isEqualTo("[\"Over 224.5\",\"Under 224.5\"]");
        assertThat(bet.path("gameId").asText()).isEqualTo(gameId);
        assertThat(Instant.parse(bet.path("locksAt").asText())).as("locks at the start, before the window ends")
                .isBeforeOrEqualTo(start.plusMillis(1));
        UUID betId = UUID.fromString(bet.path("id").asText());
        assertThat(json.readTree(mvc.perform(get("/bets/" + betId).cookie(hostSession)).andReturn().getResponse()
                .getContentAsString()).path("viewerCanOverride").asBoolean()).as("hosts may override").isTrue();
        assertThat(json.readTree(mvc.perform(get("/bets/" + betId).cookie(aliceSession)).andReturn().getResponse()
                .getContentAsString()).path("viewerCanOverride").asBoolean()).as("members may not").isFalse();

        mvc.perform(send(put("/bets/" + betId + "/pick"), aliceSession, csrf, "{\"choice\":\"Over 224.5\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(send(put("/bets/" + betId + "/pick"), bobSession, csrf, "{\"choice\":\"Under 224.5\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", betId);
        assertThat(locks.lock(betId)).isEqualTo(BetLockService.Outcome.LOCKED);

        // Live, then Final but within the grace period: nothing happens.
        jdbc.update("UPDATE games SET status = 'LIVE', home_score = 60, away_score = 58 WHERE id = ?", gameId);
        assertThat(resolutions.resolve(betId, Instant.now())).isEqualTo(SportsResolutionService.Outcome.WAITING);
        finishGame(120, 110, Instant.now());
        assertThat(resolutions.resolve(betId, Instant.now())).isEqualTo(SportsResolutionService.Outcome.WAITING);
        assertThat(resolutions.resolve(betId, Instant.now().plus(SportsOutcome.GRACE).plusSeconds(1)))
                .isEqualTo(SportsResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status || ':' || winning_choice FROM bets WHERE id = ?", String.class,
                betId)).isEqualTo("SETTLED:Over 224.5");
        assertThat(delta(betId, alice)).isEqualTo(100);
        assertThat(delta(betId, bob)).isEqualTo(-100);

        // A member can't override; a host can, and the correction nets the ledger to the new result.
        mvc.perform(send(post("/bets/" + betId + "/override"), aliceSession, csrf, "{\"winningChoice\":null}"))
                .andExpect(status().isForbidden());
        mvc.perform(send(post("/bets/" + betId + "/override"), hostSession, csrf, "{\"winningChoice\":\"Nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(send(post("/bets/" + betId + "/override"), hostSession, csrf,
                "{\"winningChoice\":\"Under 224.5\"}")).andExpect(status().isNoContent());
        assertThat(delta(betId, alice)).isEqualTo(-100);
        assertThat(delta(betId, bob)).isEqualTo(100);
        JsonNode overridden = json.readTree(mvc.perform(get("/bets/" + betId).cookie(bobSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(OpenApiSchemas.load("Bet").validate(overridden)).isEmpty();
        assertThat(overridden.path("overriddenBy").asText()).isEqualTo(host.toString());
        assertThat(overridden.path("winningChoice").asText()).isEqualTo("Under 224.5");
        assertThat(jdbc.queryForObject("""
                SELECT text FROM chat_messages WHERE table_id = ? AND system_event = 'bet_override'
                """, String.class, table)).isEqualTo("A host set “NYK + BOS combined: over or under 224.5?” to Under 224.5");
        JsonNode window = chatWindow(table, bobSession, null, null);
        assertThat(window.toString()).contains("sph set “NYK + BOS combined");

        mvc.perform(send(post("/bets/" + betId + "/override"), hostSession, csrf, "{\"winningChoice\":null}"))
                .andExpect(status().isNoContent());
        assertThat(delta(betId, alice)).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ':' || wash_reason FROM bets WHERE id = ?", String.class,
                betId)).isEqualTo("WASHED:Washed by a host");

        // After points settlement the result is final.
        jdbc.update("UPDATE bets SET points_settled_at = now() WHERE id = ?", betId);
        mvc.perform(send(post("/bets/" + betId + "/override"), hostSession, csrf,
                "{\"winningChoice\":\"Over 224.5\"}")).andExpect(status().isConflict());

        // The game is over: no new bets on it.
        mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf, proposal("give_and_take", "-3.5")))
                .andExpect(status().isConflict());
    }

    @Test
    void spreadsEndInAHalfAndWashOnCancellationAndHostsCanOverrideBeforeLock() throws Exception {
        seed("sq", Instant.now().plusSeconds(3600));
        Cookie hostSession = loginAs("sqh");
        Cookie aliceSession = loginAs("sqa");
        Csrf csrf = csrf();
        // Lines end in .5 so they can't push: a whole number (or a quarter) is refused.
        for (String whole : List.of("-3", "-3.0", "4", "-3.25")) {
            mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf, proposal("give_and_take", whole)))
                    .andExpect(status().isBadRequest());
        }
        JsonNode bet = json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf,
                proposal("give_and_take", "-3.5"))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        assertThat(bet.path("choices").toString()).isEqualTo("[\"BOS -3.5\",\"NYK +3.5\"]");
        UUID spread = UUID.fromString(bet.path("id").asText());

        // A host can wash a bet still taking picks: it locks, then washes.
        mvc.perform(send(post("/bets/" + spread + "/override"), hostSession, csrf, "{\"winningChoice\":null}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, spread))
                .isEqualTo("WASHED");

        UUID canceled = UUID.fromString(json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"),
                aliceSession, csrf, proposal("total_disaster", "200.5"))).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString()).path("id").asText());
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", canceled, alice, canceled, bob);
        jdbc.update("""
                INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, 'Over 200.5'), (?, ?, 'Under 200.5')
                """, canceled, alice, canceled, bob);
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", canceled);
        locks.lock(canceled);
        jdbc.update("UPDATE games SET status = 'POSTPONED', provider_state = 'canceled' WHERE id = ?", gameId);
        assertThat(resolutions.resolve(canceled, Instant.now())).isEqualTo(SportsResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status || ':' || wash_reason FROM bets WHERE id = ?", String.class,
                canceled)).isEqualTo("WASHED:Game canceled or postponed");
    }

    private void player(String id, String first, String last, String team) {
        jdbc.update("""
                INSERT INTO players (id, league, provider_id, first_name, last_name, position, team_abbreviation,
                                     active, updated_at)
                VALUES (?, 'NBA', ?, ?, ?, 'G', ?, true, now())
                """, id, id.substring(4), first, last, team);
    }

    /** A stat update as the feed would store it: the current line plus a history snapshot. */
    private void points(String playerId, int points, Instant at) {
        String stats = "{\"points\": " + points + "}";
        jdbc.update("""
                INSERT INTO player_game_stats (game_id, player_id, team_abbreviation, played, stats, observed_at)
                VALUES (?, ?, NULL, true, ?::jsonb, ?)
                ON CONFLICT (game_id, player_id) DO UPDATE SET stats = EXCLUDED.stats, observed_at = EXCLUDED.observed_at
                """, gameId, playerId, stats, java.sql.Timestamp.from(at));
        jdbc.update("""
                INSERT INTO player_stat_snapshots (game_id, player_id, observed_at, played, stats)
                VALUES (?, ?, ?, true, ?::jsonb)
                """, gameId, playerId, java.sql.Timestamp.from(at), stats);
    }

    private UUID propose(Cookie session, Csrf csrf, String mode, String config, int expectedStatus) throws Exception {
        String body = """
                {"modeId":"%s","gameId":"%s","config":%s,"includesOvertime":true,
                 "wagerCents":100,"durationSec":600,"forFun":false}""".formatted(mode, gameId, config);
        String response = mvc.perform(send(post("/tables/" + table + "/bets"), session, csrf, body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return expectedStatus == 201 ? UUID.fromString(json.readTree(response).path("id").asText()) : null;
    }

    private void picks(UUID bet, String aliceChoice, String bobChoice) {
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?), (?, ?)", bet, alice, bet, bob);
        jdbc.update("INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, ?), (?, ?, ?)",
                bet, alice, aliceChoice, bet, bob, bobChoice);
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", bet);
    }

    private String state(UUID bet) {
        return jdbc.queryForObject("SELECT status || ':' || coalesce(winning_choice, wash_reason, '') FROM bets WHERE id = ?",
                String.class, bet);
    }

    @Test
    void playerModesProposeFromTheGamesPlayersWashAtLockAndResolveFromStoredStats() throws Exception {
        seed("pm", Instant.now().minusSeconds(1800));
        jdbc.update("UPDATE games SET status = 'LIVE' WHERE id = ?", gameId);
        player("NBA-pm1", "Ann", "Archer", "BOS");
        player("NBA-pm2", "Ben", "Baker", "NYK");
        Instant start = Instant.now().minusSeconds(1200);
        points("NBA-pm1", 20, start);
        points("NBA-pm2", 10, start);
        Cookie aliceSession = loginAs("pma");
        Csrf csrf = csrf();

        JsonNode roster = json.readTree(mvc.perform(get("/games/" + gameId + "/players").cookie(aliceSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(roster.path("players").size()).isEqualTo(2);
        assertThat(roster.path("stats").get(0).path("key").asText()).isEqualTo("points");

        // Prop Hunt: the line must start above the player's number; crossing it before lock washes.
        propose(aliceSession, csrf, "prop_hunt", "{\"line\":15.5,\"stat\":\"points\",\"players\":[\"NBA-pm1\"]}", 409);
        UUID prop = propose(aliceSession, csrf, "prop_hunt",
                "{\"line\":24.5,\"stat\":\"points\",\"players\":[\"NBA-pm1\"]}", 201);
        assertThat(jdbc.queryForObject("SELECT question FROM bets WHERE id = ?", String.class, prop))
                .isEqualTo("Ann Archer points: over or under 24.5?");
        picks(prop, "Over 24.5", "Under 24.5");
        points("NBA-pm1", 25, Instant.now().minusSeconds(60));
        locks.lock(prop);
        assertThat(state(prop)).isEqualTo("WASHED:" + PlayerLockRules.LINE_REACHED);
        // Resending it on the same terms is a new proposal, checked the same way: he's past 24.5 now.
        propose(aliceSession, csrf, "prop_hunt", "{\"line\":24.5,\"stat\":\"points\",\"players\":[\"NBA-pm1\"]}",
                409);
        // Live stats in ticket Details: the player's number when proposed, at lock and now, against the line.
        JsonNode live = json.readTree(mvc.perform(get("/bets/" + prop + "/live").cookie(aliceSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(com.p2picks.app.OpenApiSchemas.load("BetLiveStats").validate(live)).isEmpty();
        JsonNode row = live.path("rows").get(0);
        assertThat(row.path("label").asText()).isEqualTo("Ann Archer points");
        assertThat(row.path("detail").asText()).isEqualTo("G · BOS");
        assertThat(row.path("proposed").asInt()).isEqualTo(20);
        assertThat(row.path("locked").asInt()).isEqualTo(25);
        assertThat(row.path("current").asInt()).isEqualTo(25);
        assertThat(row.path("target").decimalValue()).isEqualByComparingTo("24.5");
        assertThat(row.path("targetLabel").asText()).isEqualTo("Line");
        assertThat(live.path("home").path("abbreviation").asText()).isNotBlank();
        mvc.perform(get("/bets/" + prop + "/live")).andExpect(status().isUnauthorized());

        // King Of The Hill settles the moment someone reaches the target, mid-game.
        UUID hill = propose(aliceSession, csrf, "king_of_the_hill",
                "{\"line\":30,\"stat\":\"points\",\"players\":[\"NBA-pm1\",\"NBA-pm2\"]}", 201);
        assertThat(jdbc.queryForObject("SELECT choices::text FROM bets WHERE id = ?", String.class, hill))
                .isEqualTo("[\"Ann Archer\", \"Ben Baker\", \"Neither\"]");
        picks(hill, "Ann Archer", "Ben Baker");
        locks.lock(hill);
        assertThat(state(hill)).isEqualTo("PENDING:");
        assertThat(resolutions.resolve(hill, Instant.now())).isEqualTo(SportsResolutionService.Outcome.WAITING);
        points("NBA-pm2", 31, Instant.now());
        assertThat(resolutions.resolve(hill, Instant.now())).isEqualTo(SportsResolutionService.Outcome.RESOLVED);
        assertThat(state(hill)).isEqualTo("SETTLED:Ben Baker");
        // Once it's called, its numbers stop: Details shows them as they were then, marked closed.
        JsonNode hillAtResult = json.readTree(mvc.perform(get("/bets/" + hill + "/live").cookie(aliceSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hillAtResult.path("closed").asBoolean()).isTrue();
        assertThat(hillAtResult.path("rows").get(1).path("current").asInt()).isEqualTo(31);

        // Either Or settles after Final plus the grace period.
        UUID either = propose(aliceSession, csrf, "either_or",
                "{\"stat\":\"points\",\"players\":[\"NBA-pm1\",\"NBA-pm2\"]}", 201);
        picks(either, "Ann Archer", "Ben Baker");
        locks.lock(either);
        points("NBA-pm1", 40, Instant.now());
        finishGame(110, 100, Instant.now().minus(SportsOutcome.GRACE).minusSeconds(1));
        JsonNode eitherLive = json.readTree(mvc.perform(get("/bets/" + either + "/live").cookie(aliceSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(eitherLive.path("rows").size()).isEqualTo(2);
        assertThat(eitherLive.path("rows").get(0).path("locked").asInt()).isEqualTo(25);
        assertThat(eitherLive.path("rows").get(0).path("current").asInt()).isEqualTo(40);
        assertThat(eitherLive.path("rows").get(1).path("current").asInt()).isEqualTo(31);
        assertThat(eitherLive.path("rows").get(0).path("target").isNull()).as("Either Or has no line").isTrue();
        assertThat(resolutions.resolve(either, Instant.now())).isEqualTo(SportsResolutionService.Outcome.RESOLVED);
        assertThat(state(either)).isEqualTo("SETTLED:Ann Archer");
        // Ann's 40 and the final score came after King of the Hill was called: its numbers didn't move.
        JsonNode hillLater = json.readTree(mvc.perform(get("/bets/" + hill + "/live").cookie(aliceSession))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(hillLater).isEqualTo(hillAtResult);
        assertThat(hillLater.path("rows").get(0).path("current").asInt()).isNotEqualTo(40);
        assertThat(eitherLive.path("closed").asBoolean()).isFalse();
    }

    @Test
    void nbaScorcererBetsOnWhichTeamScoresTheNextBasketAndHow() throws Exception {
        seed("sc", Instant.now().plusSeconds(3600));
        Cookie aliceSession = loginAs("sca");
        UUID bet = propose(aliceSession, csrf(), "scorcerer", "{}", 201);
        assertThat(jdbc.queryForObject("SELECT question FROM bets WHERE id = ?", String.class, bet))
                .isEqualTo("NYK @ BOS: next basket?");
        assertThat(json.readTree(jdbc.queryForObject("SELECT choices::text FROM bets WHERE id = ?", String.class,
                bet))).extracting(JsonNode::asText)
                .containsExactly("NYK 3", "NYK 2", "BOS 3", "BOS 2", "No more scores");
    }

    @Test
    void liveTotalsMustStartAboveTheCombinedScoreAndWashIfPassedBeforeLock() throws Exception {
        seed("tl", Instant.now().minusSeconds(1800));
        jdbc.update("UPDATE games SET status = 'LIVE', home_score = 60, away_score = 58 WHERE id = ?", gameId);
        Cookie aliceSession = loginAs("tla");
        Cookie bobSession = loginAs("tlb");
        Csrf csrf = csrf();

        JsonNode rejected = json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession, csrf,
                proposal("total_disaster", "117.5"))).andExpect(status().isConflict()).andReturn().getResponse()
                .getContentAsString());
        assertThat(rejected.path("code").asText()).isEqualTo("invalid_terms");
        UUID betId = UUID.fromString(json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession,
                csrf, proposal("total_disaster", "130.5"))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).path("id").asText());
        mvc.perform(send(put("/bets/" + betId + "/pick"), aliceSession, csrf, "{\"choice\":\"Over 130.5\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(send(put("/bets/" + betId + "/pick"), bobSession, csrf, "{\"choice\":\"Under 130.5\"}"))
                .andExpect(status().isNoContent());

        // The score passes the line while picks are still open.
        jdbc.update("UPDATE games SET home_score = 70, away_score = 62 WHERE id = ?", gameId);
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", betId);
        assertThat(locks.lock(betId)).isEqualTo(BetLockService.Outcome.LOCKED);
        assertThat(jdbc.queryForObject("SELECT status || ':' || wash_reason FROM bets WHERE id = ?", String.class,
                betId)).isEqualTo("WASHED:Line reached before picks locked");
    }

    @Test
    void aBetWithNoFeedResultWashesFortyEightHoursAfterTheScheduledStart() throws Exception {
        Instant start = Instant.now().plusSeconds(120);
        seed("nr", start);
        Cookie aliceSession = loginAs("nra");
        Cookie bobSession = loginAs("nrb");
        Csrf csrf = csrf();
        UUID betId = UUID.fromString(json.readTree(mvc.perform(send(post("/tables/" + table + "/bets"), aliceSession,
                csrf, proposal("total_disaster", "224.5"))).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).path("id").asText());
        mvc.perform(send(put("/bets/" + betId + "/pick"), aliceSession, csrf, "{\"choice\":\"Over 224.5\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(send(put("/bets/" + betId + "/pick"), bobSession, csrf, "{\"choice\":\"Under 224.5\"}"))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE bets SET locks_at = now() - interval '1 second' WHERE id = ?", betId);
        assertThat(locks.lock(betId)).isEqualTo(BetLockService.Outcome.LOCKED);

        // The game goes live and the feed never reports anything again.
        jdbc.update("UPDATE games SET status = 'LIVE', home_score = 60, away_score = 58 WHERE id = ?", gameId);
        assertThat(resolutions.resolve(betId, start.plus(java.time.Duration.ofHours(47))))
                .isEqualTo(SportsResolutionService.Outcome.WAITING);
        assertThat(resolutions.resolve(betId, start.plus(java.time.Duration.ofHours(48))))
                .isEqualTo(SportsResolutionService.Outcome.RESOLVED);
        assertThat(jdbc.queryForObject("SELECT status || ':' || wash_reason FROM bets WHERE id = ?", String.class,
                betId)).isEqualTo("WASHED:No result from the feed");
        assertThat(delta(betId, alice)).isZero();
    }
}
