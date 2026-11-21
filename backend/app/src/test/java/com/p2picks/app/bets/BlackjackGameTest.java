package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlackjackGameTest {
    private static final UUID ANN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final long NOW = 1_000_000;

    private static BlackjackRules rules() {
        return BlackjackRules.defaults();
    }

    private static BlackjackRules with(String turnOrder, boolean insurance, boolean hitSoft17) {
        BlackjackRules d = BlackjackRules.defaults();
        return new BlackjackRules(d.decks(), d.minBetCents(), d.maxBetCents(), d.maxSplits(), hitSoft17,
                d.blackjackPays(), d.seats(), d.doubleOn(), d.doubleAfterSplit(), insurance, turnOrder, d.joinSec(),
                d.decisionSec());
    }

    /** One seat: cards go player, dealer up, player, dealer hole, then the rest in order. */
    private static BlackjackGame one(BlackjackRules rules, long bet, String... cards) {
        return BlackjackGame.deal(rules, List.of(new BlackjackGame.Buyer(ANN, 1, bet)), List.of(cards), NOW);
    }

    /** Taps the dealer's button until the hand pays out. */
    private static void dealerPlays(BlackjackRules rules, BlackjackGame game) {
        while (BlackjackGame.PHASE_DEALER.equals(game.phase)) game.dealerStep(rules, NOW);
    }

    @Test
    void handTotalsCountAcesAsElevenWhenTheyFit() {
        assertThat(BlackjackGame.total(List.of("AS", "6H"))).isEqualTo(17);
        assertThat(BlackjackGame.soft(List.of("AS", "6H"))).isTrue();
        assertThat(BlackjackGame.total(List.of("AS", "6H", "9C"))).isEqualTo(16);
        assertThat(BlackjackGame.total(List.of("AS", "AH", "9C"))).isEqualTo(21);
        assertThat(BlackjackGame.natural(List.of("AS", "KD"))).isTrue();
        assertThat(BlackjackGame.natural(List.of("7S", "7D", "7C"))).isFalse();
    }

    @Test
    void standingOnTwentyBeatsADealerWhoStandsOnSeventeen() {
        BlackjackGame game = one(rules(), 100, "KS", "9H", "QD", "8C");
        game.act(rules(), ANN, BlackjackGame.Action.STAND, NOW);
        dealerPlays(rules(), game);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        assertThat(game.seats.getFirst().hands.getFirst().result).isEqualTo("win");
        assertThat(game.seats.getFirst().delta).isEqualTo(100);
        assertThat(game.dealerDelta).isEqualTo(-100);
    }

    @Test
    void bustingLosesAndTheDealerDoesNotDraw() {
        BlackjackGame game = one(rules(), 100, "KS", "6H", "6D", "TC", "9S");
        game.act(rules(), ANN, BlackjackGame.Action.HIT, NOW);
        assertThat(game.seats.getFirst().hands.getFirst().result).isEqualTo("bust");
        assertThat(game.dealer).containsExactly("6H", "TC");
        assertThat(game.dealerDelta).isEqualTo(100);
    }

    @Test
    void aNaturalPaysThreeToTwoOrSixToFive() {
        BlackjackGame game = one(rules(), 100, "AS", "9H", "KD", "8C");
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        assertThat(game.seats.getFirst().hands.getFirst().result).isEqualTo("blackjack");
        assertThat(game.seats.getFirst().delta).isEqualTo(150);

        BlackjackRules sixFive = BlackjackRules.parse(new ObjectMapper().createObjectNode().put("blackjackPays", "6:5"));
        assertThat(one(sixFive, 100, "AS", "9H", "KD", "8C").seats.getFirst().delta).isEqualTo(120);
    }

    @Test
    void theDealerPeeksAndADealerNaturalEndsTheHand() {
        BlackjackGame game = one(rules(), 100, "9S", "AH", "9D", "KC");
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        assertThat(game.seats.getFirst().hands.getFirst().result).isEqualTo("lose");
        assertThat(game.seats.getFirst().delta).isEqualTo(-100);
    }

    @Test
    void insuranceIsOfferedOnAnAceAndPaysTwoToOne() {
        BlackjackRules insured = with("together", true, false);
        BlackjackGame game = one(insured, 100, "9S", "AH", "9D", "KC");
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_INSURANCE);
        assertThat(game.insuranceCost(ANN, true)).isEqualTo(50);
        game.insure(insured, ANN, true, NOW);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        // Loses the 100 bet, wins 2 x 50 insurance.
        assertThat(game.seats.getFirst().delta).isZero();
    }

    @Test
    void undecidedInsuranceIsDeclinedWhenTimeRunsOut() {
        BlackjackRules insured = with("together", true, false);
        BlackjackGame game = one(insured, 100, "9S", "AH", "9D", "7C");
        assertThat(game.timeout(insured, NOW + 30_000)).isTrue();
        assertThat(game.seats.getFirst().insurance).isZero();
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_PLAY);
    }

    @Test
    void doublingDoublesTheStakeAndTakesOneCard() {
        BlackjackGame game = one(rules(), 100, "6S", "9H", "5D", "7C", "TS", "2H");
        assertThat(game.extraStake(rules(), ANN, BlackjackGame.Action.DOUBLE)).isEqualTo(100);
        game.act(rules(), ANN, BlackjackGame.Action.DOUBLE, NOW);
        dealerPlays(rules(), game);
        BlackjackGame.Hand hand = game.seats.getFirst().hands.getFirst();
        assertThat(hand.cards).containsExactly("6S", "5D", "TS");
        assertThat(hand.stake).isEqualTo(200);
        // Dealer 16 draws a 2 to 18; player 21 wins double.
        assertThat(game.seats.getFirst().delta).isEqualTo(200);
    }

    @Test
    void doubleOnNineToElevenOnly() {
        BlackjackRules d = BlackjackRules.defaults();
        BlackjackRules restricted = new BlackjackRules(d.decks(), d.minBetCents(), d.maxBetCents(), d.maxSplits(),
                false, "3:2", d.seats(), "9-11", true, false, "together", d.joinSec(), d.decisionSec());
        BlackjackGame game = one(restricted, 100, "TS", "9H", "4D", "7C");
        assertThatThrownBy(() -> game.extraStake(restricted, ANN, BlackjackGame.Action.DOUBLE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void splittingMakesTwoHandsUpToTheLimit() {
        BlackjackGame game = one(rules(), 100, "8S", "9H", "8D", "7C", "8H", "2S", "TD", "TS", "9C", "9D");
        game.act(rules(), ANN, BlackjackGame.Action.SPLIT, NOW);
        BlackjackGame.Seat seat = game.seats.getFirst();
        assertThat(seat.hands).hasSize(2);
        assertThat(seat.hands.get(0).cards).containsExactly("8S", "8H");
        // The first hand drew another 8: split again.
        game.act(rules(), ANN, BlackjackGame.Action.SPLIT, NOW);
        assertThat(seat.hands).hasSize(3);
        assertThat(BlackjackGame.staked(seat)).isEqualTo(300);
    }

    @Test
    void maxSplitsZeroRefusesSplits() {
        BlackjackGame game = one(rules(), 100, "8S", "9H", "8D", "7C");
        BlackjackRules d = BlackjackRules.defaults();
        BlackjackRules noSplits = new BlackjackRules(d.decks(), d.minBetCents(), d.maxBetCents(), 0, false, "3:2",
                d.seats(), "any", true, false, "together", d.joinSec(), d.decisionSec());
        assertThatThrownBy(() -> game.extraStake(noSplits, ANN, BlackjackGame.Action.SPLIT))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void splitAcesGetOneCardEach() {
        BlackjackGame game = one(rules(), 100, "AS", "9H", "AD", "7C", "KS", "5H");
        game.act(rules(), ANN, BlackjackGame.Action.SPLIT, NOW);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DEALER);
        dealerPlays(rules(), game);
        // A split 21 is a plain 21, not a natural (the dealer's 16 draws from a reshuffle, so only that's fixed).
        assertThat(game.seats.getFirst().hands.getFirst().result).isNotEqualTo("blackjack");
    }

    @Test
    void theDealerHitsSoftSeventeenOnlyWhenTheRulesSaySo() {
        BlackjackGame stands = one(rules(), 100, "TS", "AH", "8D", "6C", "5S");
        stands.act(rules(), ANN, BlackjackGame.Action.STAND, NOW);
        dealerPlays(rules(), stands);
        assertThat(stands.dealer).containsExactly("AH", "6C");

        BlackjackRules h17 = with("together", false, true);
        BlackjackGame hits = one(h17, 100, "TS", "AH", "8D", "6C", "4S");
        hits.act(h17, ANN, BlackjackGame.Action.STAND, NOW);
        dealerPlays(h17, hits);
        assertThat(hits.dealer).containsExactly("AH", "6C", "4S");
    }

    @Test
    void seatOrderLetsOnlyTheCurrentSeatActAndTimeoutsStand() {
        BlackjackRules ordered = with("seats", false, false);
        // Ann, Bob, dealer; then Ann, Bob, dealer hole.
        BlackjackGame game = BlackjackGame.deal(ordered, List.of(new BlackjackGame.Buyer(ANN, 1, 100),
                new BlackjackGame.Buyer(BOB, 2, 100)), List.of("TS", "9H", "8D", "TD", "7C", "8C"), NOW);
        assertThat(game.turn).isZero();
        assertThatThrownBy(() -> game.act(ordered, BOB, BlackjackGame.Action.STAND, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(game.timeout(ordered, NOW + 30_000)).isTrue();
        assertThat(game.turn).isEqualTo(1);
        game.act(ordered, BOB, BlackjackGame.Action.STAND, NOW + 31_000);
        dealerPlays(ordered, game);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        // The dealer's 16 draws from a reshuffle, so only zero-sum is fixed.
        long players = game.seats.stream().mapToLong(s -> s.delta).sum();
        assertThat(game.dealerDelta).isEqualTo(-players);
    }

    @Test
    void togetherEveryoneActsAndTheHandEndsWhenAllAreDone() {
        BlackjackGame game = BlackjackGame.deal(rules(), List.of(new BlackjackGame.Buyer(ANN, 1, 100),
                new BlackjackGame.Buyer(BOB, 2, 300)), List.of("TS", "TH", "TC", "QD", "7C", "7D"), NOW);
        game.act(rules(), BOB, BlackjackGame.Action.STAND, NOW);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_PLAY);
        game.act(rules(), ANN, BlackjackGame.Action.STAND, NOW);
        dealerPlays(rules(), game);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        // Ann 20 beats 17, Bob 17 pushes.
        assertThat(game.seats.get(0).delta).isEqualTo(100);
        assertThat(game.seats.get(1).delta).isZero();
        assertThat(game.dealerDelta).isEqualTo(-100);
    }

    @Test
    void theDealerFlipsThenHitsThenStandsOneTapAtATimeOrTheTimerPlaysItOut() {
        // Ann 18; dealer 6 up, 10 underneath (16), then draws a 5 to 21.
        BlackjackGame game = one(rules(), 100, "TS", "6H", "8D", "TC", "5S");
        game.act(rules(), ANN, BlackjackGame.Action.STAND, NOW);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DEALER);
        assertThat(game.revealed).isFalse();
        assertThat(game.dealerMove(rules())).isEqualTo("flip");
        game.dealerStep(rules(), NOW);
        assertThat(game.revealed).isTrue();
        assertThat(game.dealerMove(rules())).isEqualTo("hit");
        game.dealerStep(rules(), NOW);
        assertThat(game.dealer).containsExactly("6H", "TC", "5S");
        assertThat(game.dealerMove(rules())).isEqualTo("stand");
        game.dealerStep(rules(), NOW);
        assertThat(game.phase).isEqualTo(BlackjackGame.PHASE_DONE);
        assertThat(game.seats.getFirst().delta).isEqualTo(-100);

        BlackjackGame idle = one(rules(), 100, "TS", "6H", "8D", "TC", "5S");
        idle.act(rules(), ANN, BlackjackGame.Action.STAND, NOW);
        assertThat(idle.nextDeadline()).isEqualTo(NOW + 30_000);
        assertThat(idle.timeout(rules(), NOW + 30_000)).isTrue();
        assertThat(idle.phase).isEqualTo(BlackjackGame.PHASE_DONE);
    }

    @Test
    void everyRandomHandIsZeroSumAndSurvivesJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            BlackjackRules rules = rules();
            List<BlackjackGame.Buyer> buyers = new ArrayList<>();
            for (int s = 0; s < 1 + random.nextInt(7); s++) {
                buyers.add(new BlackjackGame.Buyer(UUID.randomUUID(), s + 1, 10 + random.nextInt(991)));
            }
            BlackjackGame game = BlackjackGame.deal(rules, buyers, BlackjackGame.shuffledShoe(1, random), NOW);
            long now = NOW;
            while (!game.phase.equals(BlackjackGame.PHASE_DONE)) {
                BlackjackGame loaded = json.readValue(json.writeValueAsString(game), BlackjackGame.class);
                game = loaded;
                now += 1000;
                BlackjackGame.Seat seat = loaded.seats.stream()
                        .filter(x -> x.hands.stream().anyMatch(h -> !h.done))
                        .filter(x -> loaded.turn == null || loaded.seats.get(loaded.turn) == x)
                        .findFirst().orElse(null);
                if (seat == null) {
                    game.timeout(rules, now + 120_000);
                    continue;
                }
                BlackjackGame.Action action = BlackjackGame.Action.values()[random.nextInt(4)];
                try {
                    game.act(rules, seat.userId, action, now);
                } catch (IllegalStateException refused) {
                    game.act(rules, seat.userId, BlackjackGame.Action.STAND, now);
                }
            }
            long players = game.seats.stream().mapToLong(s -> s.delta).sum();
            assertThat(game.dealerDelta).isEqualTo(-players);
            for (BlackjackGame.Seat seat : game.seats) {
                assertThat(-seat.delta).isLessThanOrEqualTo(BlackjackGame.staked(seat));
                assertThat(seat.delta).isLessThanOrEqualTo(BlackjackRules.worstCase(seat.bet, rules.maxSplits()));
            }
        }
    }

    @Test
    void rulesHaveMinimumsAndMaximums() {
        ObjectMapper json = new ObjectMapper();
        assertThatThrownBy(() -> BlackjackRules.parse(json.createObjectNode().put("decks", 9)))
                .hasMessageContaining("decks");
        assertThatThrownBy(() -> BlackjackRules.parse(json.createObjectNode().put("maxSplits", 6)))
                .hasMessageContaining("maxSplits");
        assertThatThrownBy(() -> BlackjackRules.parse(json.createObjectNode().put("minBetCents", 500)
                .put("maxBetCents", 400))).hasMessageContaining("maxBetCents");
        assertThatThrownBy(() -> BlackjackRules.parse(json.createObjectNode().put("decisionSec", 5)))
                .hasMessageContaining("decisionSec");
        assertThatThrownBy(() -> BlackjackRules.parse(json.createObjectNode().put("surrender", true)))
                .hasMessageContaining("Unknown");
        BlackjackRules parsed = BlackjackRules.parse(json.createObjectNode().put("maxSplits", 5).put("seats", 1));
        assertThat(BlackjackRules.fromConfig(json.valueToTree(parsed.toMap()))).isEqualTo(parsed);
        assertThat(BlackjackRules.defaults().worstCaseFullTable()).isEqualTo(1000L * 8 * 7);
    }
}
