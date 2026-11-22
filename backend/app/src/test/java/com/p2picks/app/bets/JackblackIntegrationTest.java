package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A Jackblack game end to end: propose, join, deal, lockstep steps, the dealer, timeouts and the ledger. */
class JackblackIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Autowired
    private JackblackService jackblack;

    private record Player(UUID id, Cookie session) {}

    private Player player(String prefix) throws Exception {
        String username = prefix + Long.toString(ThreadLocalRandom.current().nextLong(1L << 30), 36);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, username, hue, password_hash, role, status)
                VALUES (?, ?, 3, ?, 'PLAYER', 'ACTIVE')
                """, id, username, passwords.encode("a secure test password"));
        return new Player(id, loginAs(username));
    }

    private UUID table(Player host, Player... members) {
        UUID table = UUID.randomUUID();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(CODE_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(CODE_ALPHABET.length())));
        }
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Casino', '🃏', ?)", table,
                code.toString());
        jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'HOST')", table, host.id());
        for (Player member : members) {
            jdbc.update("INSERT INTO table_members (table_id, user_id, role) VALUES (?, ?, 'MEMBER')", table,
                    member.id());
        }
        return table;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, Player who, Object body)
            throws Exception {
        Csrf csrf = csrf();
        request.cookie(who.session(), csrf.cookie()).header("Origin", "http://127.0.0.1:8080")
                .header("X-CSRF-TOKEN", csrf.token()).header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse propose(Player proposer, UUID table, Map<String, Object> rules) throws Exception {
        return send(post("/tables/{id}/bets", table), proposer, Map.of("modeId", "jackblack", "wagerCents", 100,
                "durationSec", 30, "forFun", false, "rules", rules));
    }

    private UUID proposed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private int join(Player who, UUID bet) throws Exception {
        return send(put("/bets/{id}/jackblack/seat", bet), who, null).getStatus();
    }

    private JsonNode game(Player who, UUID bet) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/bets/{id}/jackblack", bet).cookie(who.session()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode game = json.readTree(response.getContentAsString());
        assertThat(OpenApiSchemas.load("JackblackGame").validate(game)).isEmpty();
        return game;
    }

    private MockHttpServletResponse act(Player who, UUID bet, String action) throws Exception {
        return send(post("/bets/{id}/jackblack/actions", bet), who, Map.of("action", action));
    }

    /** Hands out the given shoes in order (top card first), then shuffled ones. */
    private static JackblackGame.Shoes stacked(String... shoes) {
        Deque<List<String>> queue = new ArrayDeque<>();
        for (String shoe : shoes) queue.add(List.of(shoe.split(" ")));
        Random random = new Random(1);
        return decks -> queue.isEmpty() ? JackblackGame.shuffledShoe(decks, random) : queue.poll();
    }

    /** Closes the join window and deals. */
    private void deal(UUID bet, JackblackGame.Shoes shoes) {
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(jackblack.deal(bet, shoes)).isTrue();
    }

    private Map<UUID, Long> ledger(UUID bet) {
        Map<UUID, Long> totals = new HashMap<>();
        jdbc.query("SELECT user_id, sum(delta_cents) AS total FROM ledger_entries WHERE bet_id = ? GROUP BY user_id",
                rs -> {
                    totals.put(rs.getObject("user_id", UUID.class), rs.getLong("total"));
                }, bet);
        return totals;
    }

    private static Player who(JsonNode id, Player... players) {
        for (Player player : players) if (player.id().toString().equals(id.asText())) return player;
        throw new AssertionError("Nobody is " + id);
    }

    @Test
    void aGameFromProposalToPayout() throws Exception {
        Player pat = player("pat");
        Player ann = player("ann");
        UUID table = table(pat, ann);
        UUID bet = proposed(propose(pat, table, Map.of("decks", 2)));
        JsonNode proposedBet = json.readTree(mvc.perform(get("/bets/{id}", bet).cookie(ann.session()))
                .andReturn().getResponse().getContentAsString());
        assertThat(proposedBet.path("modeId").asText()).isEqualTo("jackblack");
        assertThat(proposedBet.path("config").path("decks").asInt()).isEqualTo(2);

        // The proposer is in already and can't leave; Ann joins (twice is fine); it's played, not picked.
        assertThat(send(delete("/bets/{id}/jackblack/seat", bet), pat, null).getStatus()).isEqualTo(409);
        assertThat(join(ann, bet)).isEqualTo(204);
        assertThat(join(ann, bet)).isEqualTo(204);
        assertThat(send(put("/bets/{id}/pick", bet), ann, Map.of("choice", "Up")).getStatus()).isEqualTo(400);
        JsonNode waiting = game(pat, bet);
        assertThat(waiting.path("phase").asText()).isEqualTo("waiting");
        assertThat(waiting.path("players")).extracting(JsonNode::asText)
                .containsExactly(pat.id().toString(), ann.id().toString());
        // Two players, ante 1.00, doubling and one split: (4.00 + 4.00) × 1 × 1 rotation.
        assertThat(jdbc.queryForList("SELECT risk_cents FROM jackblack_players WHERE bet_id = ?", Long.class, bet))
                .containsOnly(800L);

        // Round 1: the seat's 19 against the dealer's 17. Round 2: 20 against 20.
        deal(bet, stacked("TS 7H 9D TC", "TH QS KD JC"));
        JsonNode first = game(ann, bet);
        assertThat(first.path("phase").asText()).isEqualTo("play");
        assertThat(first.path("dealer")).extracting(JsonNode::asText).containsExactly("7H", "??");
        Player dealer = who(first.path("dealerId"), pat, ann);
        Player seat = dealer == pat ? ann : pat;
        assertThat(act(dealer, bet, "stand").getStatus()).isEqualTo(409);
        assertThat(act(seat, bet, "stand").getStatus()).isEqualTo(204);

        JsonNode dealerTurn = game(seat, bet);
        assertThat(dealerTurn.path("phase").asText()).isEqualTo("dealer");
        assertThat(dealerTurn.path("dealerMove").asText()).isEqualTo("flip");
        assertThat(act(seat, bet, "deal").getStatus()).isEqualTo(409);
        assertThat(act(dealer, bet, "deal").getStatus()).isEqualTo(204);
        assertThat(game(seat, bet).path("dealerMove").asText()).isEqualTo("stand");
        assertThat(act(dealer, bet, "deal").getStatus()).isEqualTo(204);

        // Round 2: the other player deals.
        JsonNode second = game(seat, bet);
        assertThat(second.path("round").asInt()).isEqualTo(2);
        assertThat(second.path("dealerId").asText()).isEqualTo(seat.id().toString());
        assertThat(second.path("history")).hasSize(1);
        assertThat(act(dealer, bet, "stand").getStatus()).isEqualTo(204);
        assertThat(act(seat, bet, "deal").getStatus()).isEqualTo(204);
        assertThat(act(seat, bet, "deal").getStatus()).isEqualTo(204);

        JsonNode done = game(ann, bet);
        assertThat(done.path("phase").asText()).isEqualTo("done");
        assertThat(done.path("history")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("SETTLED");
        assertThat(ledger(bet)).isEqualTo(Map.of(seat.id(), 100L, dealer.id(), -100L));
    }

    @Test
    void choicesStayHiddenUntilEveryoneHasChosen() throws Exception {
        Player pat = player("pat");
        Player ann = player("ann");
        Player bob = player("bob");
        UUID table = table(pat, ann, bob);
        UUID bet = proposed(propose(pat, table, Map.of()));
        join(ann, bet);
        join(bob, bet);
        // Seats get 10+2 and 10+3; the dealer shows 9.
        deal(bet, stacked("TS TH 9D 2C 3C 8S 5D 6H"));
        JsonNode dealt = game(pat, bet);
        Player one = who(dealt.path("seats").get(0).path("userId"), pat, ann, bob);
        Player two = who(dealt.path("seats").get(1).path("userId"), pat, ann, bob);
        Player dealer = who(dealt.path("dealerId"), pat, ann, bob);

        assertThat(act(one, bet, "hit").getStatus()).isEqualTo(204);
        JsonNode seenByTwo = game(two, bet);
        JsonNode oneSeat = seenByTwo.path("seats").get(0);
        assertThat(oneSeat.path("deciding").asBoolean()).isFalse();
        assertThat(oneSeat.has("choice")).isFalse();
        assertThat(oneSeat.path("hands").get(0).path("cards")).hasSize(2);
        assertThat(game(one, bet).path("seats").get(0).path("choice").asText()).isEqualTo("hit");
        assertThat(game(dealer, bet).path("seats").get(0).has("choice")).isFalse();
        assertThat(act(one, bet, "stand").getStatus()).isEqualTo(409);

        assertThat(act(two, bet, "hit").getStatus()).isEqualTo(204);
        JsonNode after = game(dealer, bet);
        assertThat(after.path("seats").get(0).path("hands").get(0).path("cards")).extracting(JsonNode::asText)
                .containsExactly("TS", "2C", "5D");
        assertThat(after.path("seats").get(1).path("hands").get(0).path("cards")).extracting(JsonNode::asText)
                .containsExactly("TH", "3C", "6H");
    }

    @Test
    void aLapsedTimerStandsWhoeverHasntChosen() throws Exception {
        Player pat = player("pat");
        Player ann = player("ann");
        UUID table = table(pat, ann);
        UUID bet = proposed(propose(pat, table, Map.of("decisionSec", 10)));
        join(ann, bet);
        deal(bet, stacked("TS 7H 9D TC"));
        assertThat(jackblack.expire(bet)).isFalse();
        String state = jdbc.queryForObject("SELECT state::text FROM jackblack_games WHERE bet_id = ?", String.class,
                bet);
        JackblackGame game = json.readValue(state, JackblackGame.class);
        game.deadline = 1;
        jdbc.update("UPDATE jackblack_games SET state = ?::jsonb WHERE bet_id = ?", json.writeValueAsString(game),
                bet);
        assertThat(jackblack.expire(bet)).isTrue();
        JsonNode after = game(pat, bet);
        assertThat(after.path("phase").asText()).isEqualTo("dealer");
        assertThat(after.path("seats").get(0).path("hands").get(0).path("done").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT next_deadline IS NOT NULL FROM jackblack_games WHERE bet_id = ?",
                Boolean.class, bet)).isTrue();
    }

    @Test
    void aGameNobodyJoinedWashes() throws Exception {
        Player pat = player("pat");
        UUID table = table(pat, player("ann"));
        UUID bet = proposed(propose(pat, table, Map.of()));
        deal(bet, stacked());
        assertThat(jdbc.queryForMap("SELECT status, wash_reason FROM bets WHERE id = ?", bet))
                .containsEntry("status", "WASHED").containsEntry("wash_reason", JackblackService.NOT_ENOUGH);
    }

    @Test
    void everyonesWorstCaseMustFitTheirPointsLimit() throws Exception {
        Player pat = player("pat");
        Player ann = player("ann");
        Player bob = player("bob");
        Player cat = player("cat");
        UUID table = table(pat, ann, bob, cat);
        UUID bet = proposed(propose(pat, table, Map.of("maxSplits", 1)));
        // Two players: 8.00 each; three: 16.00.
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 1000)", ann.id());
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 1000)", cat.id());
        assertThat(join(ann, bet)).isEqualTo(204);
        assertThat(join(bob, bet)).isEqualTo(204);
        MockHttpServletResponse refused = send(put("/bets/{id}/jackblack/seat", bet), cat, null);
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getContentAsString()).contains("debt_cap_exceeded");
        assertThat(jdbc.queryForList("SELECT risk_cents FROM jackblack_players WHERE bet_id = ?", Long.class, bet))
                .containsOnly(1600L);

        // At the deal Ann can't cover three players, so she's dropped and the rest play two-handed.
        deal(bet, stacked());
        assertThat(jdbc.queryForList("SELECT user_id FROM jackblack_players WHERE bet_id = ?", UUID.class, bet))
                .containsExactlyInAnyOrder(pat.id(), bob.id());
        assertThat(jdbc.queryForList("SELECT risk_cents FROM jackblack_players WHERE bet_id = ?", Long.class, bet))
                .containsOnly(800L);
        assertThat(game(pat, bet).path("order")).hasSize(2);
    }

    @Test
    void hostsChooseWhoCanPropose() throws Exception {
        Player host = player("hst");
        Player ann = player("ann");
        UUID table = table(host, ann);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("jackblackAccess", "hosts"))
                .getStatus()).isEqualTo(200);
        assertThat(propose(ann, table, Map.of()).getStatus()).isEqualTo(403);
        proposed(propose(host, table, Map.of()));
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("jackblackAccess", "off")).getStatus())
                .isEqualTo(200);
        assertThat(propose(host, table, Map.of()).getStatus()).isEqualTo(403);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("jackblackAccess", "nobody"))
                .getStatus()).isEqualTo(400);
        assertThat(send(patch("/tables/{id}/settings", table), ann, Map.of("jackblackAccess", "everyone"))
                .getStatus()).isEqualTo(403);
        assertThat(propose(host, table, Map.of("decks", 9)).getStatus()).isEqualTo(400);
    }

    @Test
    void theProposersWorstCaseMustFitTheirLimit() throws Exception {
        Player pat = player("pat");
        UUID table = table(pat, player("ann"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 500)", pat.id());
        MockHttpServletResponse full = propose(pat, table, Map.of());
        assertThat(full.getStatus()).isEqualTo(409);
        assertThat(full.getContentAsString()).contains("debt_cap_exceeded");
        // No doubling or splits: (1.00 + 1.50 for a natural) = 2.50 fits 5.00.
        proposed(propose(pat, table, Map.of("doubling", false, "maxSplits", 0)));
    }
}
