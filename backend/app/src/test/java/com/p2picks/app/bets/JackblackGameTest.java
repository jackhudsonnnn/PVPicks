package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JackblackGameTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final JackblackRules RULES = JackblackRules.defaults();

    /** Hands out the given shoes in order (top card first), then shuffled ones. */
    @SafeVarargs
    private static JackblackGame.Shoes stacked(List<String>... shoes) {
        Deque<List<String>> queue = new ArrayDeque<>(List.of(shoes));
        Random random = new Random(1);
        return secret -> queue.isEmpty() ? CardShuffle.cards(secret, 0) : queue.poll();
    }

    private static List<String> cards(String spaced) {
        return List.of(spaced.split(" "));
    }

    @Test
    void everyoneDealsOnceAndTheTotalsAreZeroSum() {
        // Two players: round 1 the seat has 19 against the dealer's 17 (wins); round 2 both 20 (push).
        JackblackGame.Shoes shoes = stacked(cards("TS 7H 9D TC"), cards("TH QS KD JC"));
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B), 100, new Random(7), shoes, 0);
        assertThat(game.rounds).isEqualTo(2);
        UUID firstDealer = game.dealerId();
        UUID firstPlayer = game.order.get(1);
        assertThat(game.seats).extracting(s -> s.userId).containsExactly(firstPlayer);
        assertThat(game.seats.getFirst().hands.getFirst().cards).containsExactly("TS", "9D");
        assertThat(game.dealer).containsExactly("7H", "TC");

        game.choose(RULES, firstPlayer, JackblackGame.Action.STAND, shoes, 1);
        assertThat(game.phase).isEqualTo(JackblackGame.DEALER);
        assertThat(game.dealerMove()).isEqualTo("flip");
        game.dealerStep(RULES, shoes, 2);
        assertThat(game.dealerMove()).isEqualTo("stand");
        game.dealerStep(RULES, shoes, 3);

        // The finished round stays up for a moment; nobody can move, then the next round deals.
        assertThat(game.phase).isEqualTo(JackblackGame.RESULTS);
        assertThat(game.nextDeadline()).isEqualTo(3 + JackblackGame.ROUND_PAUSE_MS);
        assertThat(game.timeout(RULES, shoes, 4)).isFalse();
        assertThat(game.timeout(RULES, shoes, 3 + JackblackGame.ROUND_PAUSE_MS)).isTrue();

        // Round 2: the other player deals.
        assertThat(game.round).isEqualTo(1);
        assertThat(game.dealerId()).isEqualTo(firstPlayer);
        game.choose(RULES, firstDealer, JackblackGame.Action.STAND, shoes, 4);
        game.dealerStep(RULES, shoes, 5);
        game.dealerStep(RULES, shoes, 6);

        assertThat(game.phase).isEqualTo(JackblackGame.DONE);
        assertThat(game.totals).containsEntry(firstPlayer, 100L).containsEntry(firstDealer, -100L);
        assertThat(game.history).hasSize(2);
        assertThat(game.history.get(1).seats.getFirst().hands.getFirst().result).isEqualTo("push");
        assertThat(game.nextDeadline()).isNull();
    }

    @Test
    void newCardsComeOutOnlyOnceEveryoneHasChosen() {
        // Three players: seats 1 and 2 get 10+2 and 10+3; the dealer shows 9.
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B, C), 100, new Random(3),
                stacked(cards("TS TH 9D 2C 3C 8S 5D 6H")), 0);
        UUID first = game.seats.get(0).userId;
        UUID second = game.seats.get(1).userId;
        game.choose(RULES, first, JackblackGame.Action.HIT, stacked(), 1);
        // Nothing dealt yet, and the first player can't change their mind.
        assertThat(game.seats.get(0).hands.getFirst().cards).hasSize(2);
        assertThat(game.deciding(first)).isFalse();
        assertThat(game.deciding(second)).isTrue();
        assertThatThrownBy(() -> game.choose(RULES, first, JackblackGame.Action.STAND, stacked(), 1))
                .isInstanceOf(IllegalStateException.class);

        game.choose(RULES, second, JackblackGame.Action.HIT, stacked(), 2);
        // Both cards come out together, in seat order.
        assertThat(game.seats.get(0).hands.getFirst().cards).containsExactly("TS", "2C", "5D");
        assertThat(game.seats.get(1).hands.getFirst().cards).containsExactly("TH", "3C", "6H");
        assertThat(game.deciding(first)).isTrue();
        assertThat(game.deadline).isEqualTo(2 + RULES.decisionSec() * 1000L);
    }

    @Test
    void aLapsedStepStandsWhoeverHasntChosen() {
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B, C), 100, new Random(3),
                stacked(cards("TS TH 9D 2C 3C 8S 5D 6H")), 0);
        UUID first = game.seats.get(0).userId;
        UUID second = game.seats.get(1).userId;
        game.choose(RULES, first, JackblackGame.Action.HIT, stacked(), 1);
        assertThat(game.timeout(RULES, stacked(), game.deadline - 1)).isFalse();
        assertThat(game.timeout(RULES, stacked(), game.deadline)).isTrue();
        assertThat(game.seats.get(0).hands.getFirst().cards).hasSize(3);
        assertThat(game.seats.get(1).hands.getFirst().cards).hasSize(2);
        assertThat(game.seats.get(1).hands.getFirst().done).isTrue();
        assertThat(game.deciding(second)).isFalse();
    }

    @Test
    void anIdleDealerPlaysOutOnTheTimer() {
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B), 100, new Random(7),
                stacked(cards("TS 6H 9D TC 5S 9S")), 0);
        game.choose(RULES, game.seats.getFirst().userId, JackblackGame.Action.STAND, stacked(), 1);
        assertThat(game.timeout(RULES, stacked(), game.deadline)).isTrue();
        // Flip (16), hit 5 (21), stand: the seat's 19 loses and round 2 is dealt.
        assertThat(game.history.getFirst().dealer).containsExactly("6H", "TC", "5S");
        assertThat(game.history.getFirst().dealerDelta).isEqualTo(100);
        assertThat(game.round).isEqualTo(1);
    }

    @Test
    void aDealerNaturalEndsTheRoundAtOnce() {
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B, C), 100, new Random(3),
                stacked(cards("TS AH AD 9C KC TD"), cards("2S 3S 4S 5S 6S 7S 8S 9S TS")), 0);
        JackblackGame.Round first = game.history.getFirst();
        // Seat one's 19 loses; seat two's natural pushes.
        assertThat(first.seats.get(0).hands.getFirst().result).isEqualTo("lose");
        assertThat(first.seats.get(1).hands.getFirst().result).isEqualTo("push");
        assertThat(first.dealerDelta).isEqualTo(100);
        assertThat(game.round).isEqualTo(1);
    }

    @Test
    void naturalsPayByTheRulesRoundedDown() {
        JackblackRules sixFive = new JackblackRules(1, true, 1, "6:5", 30, 2, 7);
        JackblackGame game = JackblackGame.start(sixFive, List.of(A, B), 105, new Random(7),
                stacked(cards("AS 9H KD 9C")), 0);
        // The seat's natural is done at once, and with no live hand left the dealer doesn't play.
        assertThat(game.history.getFirst().seats.getFirst().hands.getFirst().result).isEqualTo("blackjack");
        assertThat(game.history.getFirst().seats.getFirst().delta).isEqualTo(126);
    }

    @Test
    void splitAndDoubleFollowTheRules() {
        JackblackRules noSplits = new JackblackRules(1, false, 0, "3:2", 30, 2, 7);
        JackblackGame game = JackblackGame.start(noSplits, List.of(A, B), 100, new Random(7),
                stacked(cards("8S 9H 8D 7C")), 0);
        UUID player = game.seats.getFirst().userId;
        JackblackGame.Seat seat = game.seats.getFirst();
        assertThat(game.canSplit(noSplits, seat, JackblackGame.current(seat))).isFalse();
        assertThatThrownBy(() -> game.choose(noSplits, player, JackblackGame.Action.DOUBLE, stacked(), 1))
                .isInstanceOf(IllegalStateException.class);

        JackblackGame split = JackblackGame.start(RULES, List.of(A, B), 100, new Random(7),
                stacked(cards("8S 9H 8D 7C 3S 2D 9S")), 0);
        UUID splitter = split.seats.getFirst().userId;
        split.choose(RULES, splitter, JackblackGame.Action.SPLIT, stacked(), 1);
        JackblackGame.Seat two = split.seats.getFirst();
        assertThat(two.hands).hasSize(2);
        assertThat(two.hands.get(0).cards).containsExactly("8S", "3S");
        assertThat(two.hands.get(1).cards).containsExactly("8D", "2D");
        // One split allowed; doubling the first split hand (11) still is.
        assertThat(split.canSplit(RULES, two, two.hands.get(0))).isFalse();
        split.choose(RULES, splitter, JackblackGame.Action.DOUBLE, stacked(), 2);
        assertThat(two.hands.get(0).stake).isEqualTo(200);
        assertThat(two.hands.get(0).cards).containsExactly("8S", "3S", "9S");
    }

    @Test
    void randomGamesStayZeroSumAndInsideTheWorstCase() {
        Random random = new Random(42);
        JackblackGame.Action[] actions = JackblackGame.Action.values();
        for (int g = 0; g < 400; g++) {
            JackblackRules rules = new JackblackRules(1 + random.nextInt(3),
                    random.nextBoolean(), random.nextInt(4), random.nextBoolean() ? "3:2" : "6:5", 10, 2, 7);
            int count = 2 + random.nextInt(6);
            List<UUID> players = new ArrayList<>();
            for (int i = 0; i < count; i++) players.add(UUID.randomUUID());
            long ante = 10 + random.nextInt(991);
            JackblackGame game = JackblackGame.start(rules, players, ante, random, JackblackGame.FROM_SECRETS,
                    0);
            long now = 0;
            int guard = 0;
            while (!JackblackGame.DONE.equals(game.phase)) {
                assertThat(guard++).isLessThan(10_000);
                now += 1;
                if (JackblackGame.DEALER.equals(game.phase)) {
                    if (random.nextInt(4) == 0) game.timeout(rules, JackblackGame.FROM_SECRETS, game.deadline);
                    else game.dealerStep(rules, JackblackGame.FROM_SECRETS, now);
                    continue;
                }
                if (random.nextInt(6) == 0) {
                    game.timeout(rules, JackblackGame.FROM_SECRETS, game.deadline);
                    continue;
                }
                for (UUID player : players) {
                    if (!game.deciding(player)) continue;
                    JackblackGame.Action action = actions[random.nextInt(actions.length)];
                    try {
                        game.choose(rules, player, action, JackblackGame.FROM_SECRETS, now);
                    } catch (IllegalStateException refused) {
                        game.choose(rules, player, JackblackGame.Action.HIT, JackblackGame.FROM_SECRETS, now);
                    }
                }
            }
            assertThat(game.history).hasSize(count * rules.rotations());
            assertThat(game.totals.values().stream().mapToLong(Long::longValue).sum()).isZero();
            long worst = rules.worstCase(ante, count);
            for (long total : game.totals.values()) assertThat(-total).isLessThanOrEqualTo(worst);
            for (UUID player : players) {
                assertThat(game.history.stream().filter(r -> r.dealerId.equals(player))).hasSize(rules.rotations());
            }
        }
    }

    @Test
    void theStateSurvivesJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JackblackGame game = JackblackGame.start(RULES, List.of(A, B, C), 100, new Random(3),
                stacked(cards("TS TH 9D 2C 3C 8S 5D 6H")), 0);
        game.choose(RULES, game.seats.get(0).userId, JackblackGame.Action.HIT, stacked(), 1);
        JackblackGame back = json.readValue(json.writeValueAsString(game), JackblackGame.class);
        assertThat(json.writeValueAsString(back)).isEqualTo(json.writeValueAsString(game));
        assertThat(back.seats.get(0).pending).isEqualTo("HIT");
    }
}
