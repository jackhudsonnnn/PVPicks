package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.OpenApiSchemas;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A Hold'em game end to end: propose, join, deal, hands, privacy, showing, the pause, the audit and the ledger. */
class HoldemIntegrationTest extends IntegrationTestSupport {
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Autowired
    private HoldemService holdem;

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
        jdbc.update("INSERT INTO tables (id, name, emoji, quickpick_code) VALUES (?, 'Poker', '♠', ?)", table,
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

    private MockHttpServletResponse propose(Player proposer, UUID table, long bigBlind, boolean forFun)
            throws Exception {
        return send(post("/tables/{id}/bets", table), proposer, Map.of("modeId", "holdem", "wagerCents", bigBlind,
                "durationSec", 30, "forFun", forFun, "rules", Map.of("rotations", 1, "decisionSec", 20)));
    }

    private UUID proposed(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private JsonNode game(Player who, UUID bet) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/bets/{id}/holdem", bet).cookie(who.session()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode game = json.readTree(response.getContentAsString());
        assertThat(OpenApiSchemas.load("HoldemGame").validate(game)).isEmpty();
        return game;
    }

    private MockHttpServletResponse act(Player who, UUID bet, String action) throws Exception {
        return send(post("/bets/{id}/holdem/actions", bet), who, Map.of("action", action));
    }

    private void deal(UUID bet, String... decks) {
        java.util.Deque<List<String>> queue = new java.util.ArrayDeque<>();
        for (String deck : decks) queue.add(List.of(deck.split(" ")));
        holdem.dealFrom(secret -> queue.isEmpty() ? CardShuffle.cards(secret, 0) : queue.poll());
        jdbc.update("UPDATE bets SET locks_at = clock_timestamp() - interval '1 second' WHERE id = ?", bet);
        assertThat(holdem.deal(bet)).isTrue();
    }

    /** Ends the pause between hands now, so the next hand deals. */
    private void skipPause(UUID bet) throws Exception {
        String state = jdbc.queryForObject("SELECT state::text FROM card_games WHERE bet_id = ?", String.class, bet);
        HoldemGame game = json.readValue(state, HoldemGame.class);
        assertThat(game.phase).isEqualTo(HoldemGame.RESULTS);
        game.deadline = 1;
        jdbc.update("UPDATE card_games SET state = ?::jsonb WHERE bet_id = ?", json.writeValueAsString(game), bet);
        assertThat(holdem.expire(bet)).isTrue();
    }

    private Map<UUID, Long> ledger(UUID bet) {
        Map<UUID, Long> totals = new HashMap<>();
        jdbc.query("SELECT user_id, sum(delta_cents) AS total FROM ledger_entries WHERE bet_id = ? GROUP BY user_id",
                rs -> {
                    totals.put(rs.getObject("user_id", UUID.class), rs.getLong("total"));
                }, bet);
        return totals;
    }

    @AfterEach
    void shuffleAgain() {
        holdem.dealFrom(null);
    }

    @Test
    void aGameFromProposalToPayoutShowsOnlyWhatEachPlayerMaySee() throws Exception {
        Player pat = player("pat");
        Player ann = player("ann");
        Player cat = player("cat");
        UUID table = table(pat, ann, cat);
        UUID bet = proposed(propose(pat, table, 100, false));
        // Both buy-ins, 200 big blinds, are held against the points limit.
        assertThat(jdbc.queryForList("SELECT risk_cents FROM card_players WHERE bet_id = ?", Long.class, bet))
                .containsOnly(20_000L);
        assertThat(send(put("/bets/{id}/holdem/seat", bet), ann, null).getStatus()).isEqualTo(204);
        assertThat(game(cat, bet).path("phase").asText()).isEqualTo("waiting");

        // Hand 1: seat 0 (after the button, the big blind heads-up) gets aces, the button kings.
        deal(bet, "AS KD AH KC 2C 7D 9H JS 3D", "QS 2D QH 3C 4C 8D TH JD 5S");
        JsonNode asCat = game(cat, bet);
        Player button = asCat.path("buttonId").asText().equals(pat.id().toString()) ? pat : ann;
        Player big = button == pat ? ann : pat;
        assertThat(asCat.path("phase").asText()).isEqualTo("play");
        assertThat(asCat.path("toActId").asText()).isEqualTo(button.id().toString());
        assertThat(asCat.path("pot").asLong()).isEqualTo(150);
        // A member who isn't playing sees no hole cards at all; each player only their own.
        assertThat(asCat.has("me")).isFalse();
        assertThat(game(big, bet).path("me").path("hole")).extracting(JsonNode::asText).containsExactly("AS", "AH");
        JsonNode asButton = game(button, bet);
        assertThat(asButton.path("me").path("hole")).extracting(JsonNode::asText).containsExactly("KD", "KC");
        assertThat(asButton.path("me").path("toCall").asLong()).isEqualTo(50);
        assertThat(asButton.toString()).doesNotContain("\"AS\"");
        assertThat(asButton.path("shuffles")).hasSize(2);
        assertThat(asButton.path("shuffles").get(0).has("seed")).isFalse();

        // Out of turn, or a raise below the minimum: refused with the reason.
        MockHttpServletResponse early = act(big, bet, "check");
        assertThat(early.getStatus()).isEqualTo(409);
        assertThat(early.getContentAsString()).contains("holdem_refused").contains("isn't your turn");
        MockHttpServletResponse small = send(post("/bets/{id}/holdem/actions", bet), button,
                Map.of("action", "raise", "amount", 150));
        assertThat(small.getContentAsString()).contains("smallest raise is to 200");
        // Picks don't apply to a card game.
        assertThat(send(put("/bets/{id}/pick", bet), cat, Map.of("choice", "Up")).getStatus()).isEqualTo(400);

        assertThat(act(button, bet, "call").getStatus()).isEqualTo(204);
        assertThat(act(big, bet, "check").getStatus()).isEqualTo(204);
        // After the flop your hand is named (aces with 2C 7D 9H: a pair of aces).
        assertThat(game(big, bet).path("me").path("handName").asText()).isEqualTo("Pair of aces");
        assertThat(game(cat, bet).has("me")).isFalse();
        // A pre-move is armed on the server, seen only by whoever armed it, and played when the turn arrives.
        assertThat(act(big, bet, "check_fold").getStatus()).isEqualTo(409);
        assertThat(act(button, bet, "check_fold").getStatus()).isEqualTo(204);
        assertThat(game(button, bet).path("me").path("preMove").asText()).isEqualTo("check_fold");
        assertThat(game(big, bet).path("me").has("preMove")).isFalse();
        assertThat(game(big, bet).toString()).doesNotContain("check_fold");
        assertThat(act(big, bet, "check").getStatus()).isEqualTo(204);
        JsonNode turned = game(button, bet);
        assertThat(turned.path("street").asText()).isEqualTo("turn");
        assertThat(turned.path("me").has("preMove")).isFalse();
        for (int street = 0; street < 2; street++) {
            assertThat(act(big, bet, "check").getStatus()).isEqualTo(204);
            assertThat(act(button, bet, "check").getStatus()).isEqualTo(204);
        }
        JsonNode results = game(cat, bet);
        assertThat(results.path("phase").asText()).isEqualTo("results");
        JsonNode first = results.path("history").get(0);
        assertThat(first.path("pots").get(0).path("winners").get(0).asText()).isEqualTo(big.id().toString());
        assertThat(first.path("pots").get(0).path("handName").asText()).isEqualTo("Pair of aces");
        // Everyone still in shows at the showdown (no mucking); nobody's other hole cards are sent until the end.
        assertThat(first.path("shown").has(big.id().toString())).isTrue();
        assertThat(first.path("shown").has(button.id().toString())).isTrue();
        assertThat(first.has("holes")).isFalse();
        assertThat(act(button, bet, "show").getStatus()).isEqualTo(400);
        skipPause(bet);

        // Hand 2: the button moves; it folds to the big blind.
        JsonNode second = game(cat, bet);
        assertThat(second.path("hand").asInt()).isEqualTo(2);
        assertThat(second.path("buttonId").asText()).isEqualTo(big.id().toString());
        assertThat(act(big, bet, "fold").getStatus()).isEqualTo(204);
        skipPause(bet);

        JsonNode done = game(cat, bet);
        assertThat(done.path("phase").asText()).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT status FROM bets WHERE id = ?", String.class, bet)).isEqualTo("SETTLED");
        // Hand 1: +100 for the aces. Hand 2: the aces player had the button and folded its 50 small blind.
        assertThat(ledger(bet)).isEqualTo(Map.of(big.id(), 50L, button.id(), -50L));
        assertThat(done.path("totals").path(big.id().toString()).asLong()).isEqualTo(50);
        // After the game the audit reveals every deck and every hand.
        assertThat(done.path("history").get(1).has("holes")).isTrue();
        for (JsonNode shuffle : done.path("shuffles")) {
            CardShuffle.Secret secret = new CardShuffle.Secret(shuffle.path("round").asInt(),
                    shuffle.path("decks").asInt(), shuffle.path("seed").asText());
            assertThat(secret.decks).isEqualTo(1);
            assertThat(secret.commitment()).isEqualTo(shuffle.path("commitment").asText());
        }
    }

    @Test
    void hostsChooseWhoCanProposeHoldem() throws Exception {
        Player host = player("host");
        Player ann = player("ann");
        UUID table = table(host, ann);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("holdemAccess", "hosts"))
                .getStatus()).isEqualTo(200);
        assertThat(propose(ann, table, 100, false).getStatus()).isEqualTo(403);
        assertThat(send(patch("/tables/{id}/settings", table), host, Map.of("holdemAccess", "sometimes"))
                .getStatus()).isEqualTo(400);
        assertThat(propose(host, table, 5, false).getStatus()).isEqualTo(400);
        // 4fun: a 0 big blind (play chips), and nothing held against the limit.
        UUID fun = proposed(propose(host, table, 0, true));
        assertThat(jdbc.queryForList("SELECT risk_cents FROM card_players WHERE bet_id = ?", Long.class, fun))
                .containsOnly(0L);
    }

    @Test
    void theProposersTwoBuyInsMustFitTheirLimit() throws Exception {
        Player pat = player("pat");
        UUID table = table(pat, player("ann"));
        jdbc.update("INSERT INTO debt_caps (user_id, cap_cents) VALUES (?, 10000)", pat.id());
        MockHttpServletResponse full = propose(pat, table, 100, false);
        assertThat(full.getStatus()).isEqualTo(409);
        assertThat(full.getContentAsString()).contains("debt_cap_exceeded");
        proposed(propose(pat, table, 50, false));
    }
}
