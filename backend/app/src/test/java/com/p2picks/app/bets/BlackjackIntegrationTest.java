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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A Blackjack hand end to end: propose, sit, deal, decisions, timeouts and the ledger (domain rules). */
class BlackjackIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Autowired
    private BlackjackService blackjack;

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

    private MockHttpServletResponse propose(Player dealer, UUID table, Map<String, Object> rules) throws Exception {
        return send(post("/tables/{id}/bets", table), dealer,
                Map.of("modeId", "blackjack", "forFun", false, "rules", rules));
    }

    private UUID proposed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private JsonNode hand(Player who, UUID bet) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/bets/{id}/blackjack", bet).cookie(who.session()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode hand = json.readTree(response.getContentAsString());
        assertThat(OpenApiSchemas.load("BlackjackHand").validate(hand)).isEmpty();
        return hand;
    }

    private MockHttpServletResponse act(Player who, UUID bet, String action) throws Exception {
        return send(post("/bets/{id}/blackjack/actions", bet), who, Map.of("action", action));
    }

    /** Closes the join window and deals from a stacked shoe (top card first), so the hand is known. */
    private void deal(UUID bet, String... cards) {
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(blackjack.deal(bet, rules -> List.of(cards))).isTrue();
    }

    private Map<UUID, Long> ledger(UUID bet) {
        Map<UUID, Long> totals = new java.util.HashMap<>();
        jdbc.query("SELECT user_id, sum(delta_cents) AS total FROM ledger_entries WHERE bet_id = ? GROUP BY user_id",
                rs -> {
                    totals.put(rs.getObject("user_id", UUID.class), rs.getLong("total"));
                }, bet);
        return totals;
    }

    @Test
    void aHandFromProposalToPayout() throws Exception {
        Player dealer = player("dlr");
        Player ann = player("ann");
        Player bob = player("bob");
        UUID table = table(dealer, ann, bob);
        UUID bet = proposed(propose(dealer, table, Map.of("minBetCents", 100, "maxBetCents", 500, "seats", 3,
                "joinSec", 30, "decisionSec", 10)));
        JsonNode proposedBet = json.readTree(mvc.perform(get("/bets/{id}", bet).cookie(ann.session()))
                .andReturn().getResponse().getContentAsString());
        assertThat(proposedBet.path("modeId").asText()).isEqualTo("blackjack");
        assertThat(proposedBet.path("config").path("maxBetCents").asInt()).isEqualTo(500);

        // Seats: within the dealer's limits, not the dealer, and a bet can change before the deal.
        assertThat(send(put("/bets/{id}/blackjack/seat", bet), ann, Map.of("betCents", 50)).getStatus())
                .isEqualTo(409);
        assertThat(send(put("/bets/{id}/blackjack/seat", bet), dealer, Map.of("betCents", 100)).getStatus())
                .isEqualTo(409);
        assertThat(send(put("/bets/{id}/blackjack/seat", bet), ann, Map.of("betCents", 100)).getStatus())
                .isEqualTo(204);
        assertThat(send(put("/bets/{id}/blackjack/seat", bet), ann, Map.of("betCents", 200)).getStatus())
                .isEqualTo(204);
        assertThat(send(put("/bets/{id}/blackjack/seat", bet), bob, Map.of("betCents", 100)).getStatus())
                .isEqualTo(204);
        // Blackjack is played at the seats, not picked.
        assertThat(send(put("/bets/{id}/pick", bet), bob, Map.of("choice", "Players")).getStatus()).isEqualTo(400);
        JsonNode waiting = hand(dealer, bet);
        assertThat(waiting.path("phase").asText()).isEqualTo("waiting");
        assertThat(waiting.path("seats")).hasSize(2);
        assertThat(waiting.path("seats").get(0).path("betCents").asInt()).isEqualTo(200);

        // Ann 20, Bob 16, dealer 9 up with a 7 underneath (16); Bob hits a 5 (21); the dealer draws a 10 and busts.
        deal(bet, "KS", "9H", "9D", "QD", "7C", "7S", "5H", "TC");
        JsonNode dealt = hand(bob, bet);
        assertThat(dealt.path("phase").asText()).isEqualTo("play");
        assertThat(dealt.path("dealer")).extracting(JsonNode::asText).containsExactly("9D", "??");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("PENDING");

        assertThat(act(dealer, bet, "hit").getStatus()).isEqualTo(403);
        assertThat(act(ann, bet, "stand").getStatus()).isEqualTo(204);
        assertThat(act(ann, bet, "hit").getStatus()).isEqualTo(409);
        assertThat(act(bob, bet, "hit").getStatus()).isEqualTo(204);

        // The dealer's turn: one button, tapped by the dealer only (flip, hit 10 to bust, stand isn't needed).
        JsonNode dealerTurn = hand(ann, bet);
        assertThat(dealerTurn.path("phase").asText()).isEqualTo("dealer");
        assertThat(dealerTurn.path("dealerMove").asText()).isEqualTo("flip");
        assertThat(act(ann, bet, "dealer").getStatus()).isEqualTo(403);
        assertThat(act(dealer, bet, "dealer").getStatus()).isEqualTo(204);
        assertThat(hand(ann, bet).path("dealerMove").asText()).isEqualTo("hit");
        assertThat(act(dealer, bet, "dealer").getStatus()).isEqualTo(204);

        JsonNode done = hand(ann, bet);
        assertThat(done.path("phase").asText()).isEqualTo("done");
        assertThat(done.path("dealer")).extracting(JsonNode::asText).containsExactly("9D", "7S", "TC");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("SETTLED");
        assertThat(ledger(bet)).isEqualTo(Map.of(ann.id(), 200L, bob.id(), 100L, dealer.id(), -300L));
    }

    @Test
    void idlePlayersStandWhenTheirTimerRunsOutAndSeatsTakeTurns() throws Exception {
        Player dealer = player("dlr");
        Player ann = player("ann");
        Player bob = player("bob");
        UUID table = table(dealer, ann, bob);
        UUID bet = proposed(propose(dealer, table, Map.of("turnOrder", "seats", "decisionSec", 10)));
        send(put("/bets/{id}/blackjack/seat", bet), ann, Map.of("betCents", 100));
        send(put("/bets/{id}/blackjack/seat", bet), bob, Map.of("betCents", 100));
        // Ann 15, Bob 20, dealer 10 + 8 = 18.
        deal(bet, "8S", "KS", "TH", "7D", "QH", "8C");
        assertThat(hand(ann, bet).path("turnSeat").asInt()).isEqualTo(1);
        assertThat(act(bob, bet, "stand").getStatus()).isEqualTo(409);

        // Ann's timer lapses: she stands, and it's Bob's turn.
        String state = jdbc.queryForObject("SELECT state::text FROM blackjack_rounds WHERE bet_id = ?", String.class,
                bet);
        BlackjackGame game = json.readValue(state, BlackjackGame.class);
        game.seats.getFirst().deadline = 1;
        jdbc.update("UPDATE blackjack_rounds SET state = ?::jsonb WHERE bet_id = ?", json.writeValueAsString(game),
                bet);
        assertThat(blackjack.expire(bet)).isTrue();
        assertThat(hand(ann, bet).path("turnSeat").asInt()).isEqualTo(2);
        assertThat(act(bob, bet, "stand").getStatus()).isEqualTo(204);
        // Dealer 18: flip, then stand.
        assertThat(act(dealer, bet, "dealer").getStatus()).isEqualTo(204);
        assertThat(act(dealer, bet, "dealer").getStatus()).isEqualTo(204);
        assertThat(ledger(bet)).isEqualTo(Map.of(ann.id(), -100L, bob.id(), 100L));
    }

    @Test
    void nobodySittingWashesTheHand() throws Exception {
        Player dealer = player("dlr");
        UUID table = table(dealer, player("ann"));
        UUID bet = proposed(propose(dealer, table, Map.of()));
        deal(bet);
        assertThat(jdbc.queryForMap("SELECT status, wash_reason FROM bets WHERE id = ?", bet))
                .containsEntry("status", "WASHED").containsEntry("wash_reason", BlackjackService.NOBODY_SAT);
    }

    @Test
    void theDealersWorstCaseMustFitTheirPointsLimit() throws Exception {
        Player dealer = player("dlr");
        UUID table = table(dealer, player("ann"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 10000)", dealer.id());
        // 7 seats x 10 points x 8 (3 splits, all doubled) = 560 points.
        MockHttpServletResponse full = propose(dealer, table, Map.of());
        assertThat(full.getStatus()).isEqualTo(409);
        assertThat(full.getContentAsString()).contains("debt_cap_exceeded");
        // 2 seats x 5 points x 2 (no splits) = 20 points fits 100.
        proposed(propose(dealer, table, Map.of("seats", 2, "maxBetCents", 500, "maxSplits", 0)));
    }

    @Test
    void hostsChooseWhoCanDeal() throws Exception {
        Player host = player("hst");
        Player ann = player("ann");
        UUID table = table(host, ann);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("blackjackAccess", "hosts")).getStatus())
                .isEqualTo(200);
        assertThat(propose(ann, table, Map.of()).getStatus()).isEqualTo(403);
        proposed(propose(host, table, Map.of()));
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("blackjackAccess", "off")).getStatus())
                .isEqualTo(200);
        assertThat(propose(host, table, Map.of()).getStatus()).isEqualTo(403);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("blackjackAccess", "nobody"))
                .getStatus()).isEqualTo(400);
        assertThat(send(patch("/tables/{id}/settings", table), ann, Map.of("blackjackAccess", "everyone"))
                .getStatus()).isEqualTo(403);
        assertThat(propose(host, table, Map.of("decks", 9)).getStatus()).isEqualTo(400);
    }

    @Test
    void inPlayStakesCountTowardThePointsLimit() throws Exception {
        Player dealer = player("dlr");
        Player ann = player("ann");
        UUID table = table(dealer, ann);
        UUID bet = proposed(propose(dealer, table, Map.of("maxSplits", 1)));
        send(put("/bets/{id}/blackjack/seat", bet), ann, Map.of("betCents", 300));
        // Ann 11 (doubles), dealer 10 + 7.
        deal(bet, "6S", "TH", "5D", "7D", "KC");
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 500)", ann.id());
        // Doubling would put 600 at risk against a 500 limit.
        assertThat(act(ann, bet, "double").getContentAsString()).contains("debt_cap_exceeded");
        assertThat(jdbc.queryForObject("SELECT stake_cents FROM blackjack_seats WHERE bet_id = ? AND user_id = ?",
                Long.class, bet, ann.id())).isEqualTo(300);
        assertThat(act(ann, bet, "stand").getStatus()).isEqualTo(204);
        assertThat(send(delete("/bets/{id}/blackjack/seat", bet), ann, null).getStatus()).isEqualTo(409);
    }
}
