package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.app.bets.HoldemGame.Action;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldemGameTest {
    private static final HoldemRules RULES = new HoldemRules(30, 2, 9);
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final HoldemGame.Decks DECKS = HoldemGame.FROM_SECRETS;

    /** Sets everyone's hole cards (in seat order) and what's left in the deck (the board, top first). */
    private static void rig(HoldemGame game, String deck, String... holes) {
        for (int i = 0; i < holes.length; i++) game.seats.get(i).hole = new ArrayList<>(HandEvaluator.cards(holes[i]));
        game.deck = new ArrayList<>(HandEvaluator.cards(deck));
    }

    private static UUID turn(HoldemGame game) {
        return game.toActId();
    }

    private static long sum(HoldemGame game) {
        return game.stacks.values().stream().mapToLong(Long::longValue).sum() + game.pot();
    }

    @Test
    void headsUpTheButtonPostsTheSmallBlindAndActsFirstBeforeTheFlopOnly() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B), 100, new Random(1), DECKS, 0);
        assertThat(game.hands).isEqualTo(2);
        // Everyone starts on 10 big blinds.
        assertThat(game.stack).isEqualTo(1_000);
        UUID button = game.buttonId;
        UUID other = button.equals(A) ? B : A;
        assertThat(game.smallBlindId).isEqualTo(button);
        assertThat(game.bigBlindId).isEqualTo(other);
        assertThat(game.stacks.get(button)).isEqualTo(950);
        assertThat(game.stacks.get(other)).isEqualTo(900);
        // Seats run from the player after the button, so seat 0 is the big blind, seat 1 the button.
        rig(game, "2C 7D 9H JS 3D", "KS KH", "AS AH");
        assertThat(turn(game)).isEqualTo(button);
        assertThatThrownBy(() -> game.act(RULES, button, Action.CHECK, 0, DECKS, 1))
                .isInstanceOf(NotAllowed.class);
        assertThatThrownBy(() -> game.act(RULES, other, Action.CALL, 0, DECKS, 1))
                .isInstanceOf(NotAllowed.class).hasMessage("It isn't your turn");
        game.act(RULES, button, Action.CALL, 0, DECKS, 1);
        // The big blind still has its option.
        assertThat(turn(game)).isEqualTo(other);
        game.act(RULES, other, Action.CHECK, 0, DECKS, 2);
        assertThat(game.street).isEqualTo(HoldemGame.FLOP);
        assertThat(game.board).containsExactly("2C", "7D", "9H");
        // After the flop the big blind acts first.
        assertThat(turn(game)).isEqualTo(other);
        for (int street = 0; street < 3; street++) {
            game.act(RULES, other, Action.CHECK, 0, DECKS, 3);
            game.act(RULES, button, Action.CHECK, 0, DECKS, 4);
        }
        assertThat(game.phase).isEqualTo(HoldemGame.RESULTS);
        HoldemGame.Hand hand = game.history.getFirst();
        assertThat(hand.showdown).isTrue();
        assertThat(hand.pots).singleElement().satisfies(pot -> {
            assertThat(pot.amount).isEqualTo(200);
            assertThat(pot.winners).containsExactly(button);
            assertThat(pot.handName).isEqualTo("Pair of aces");
        });
        // Everyone still in shows: no mucking, since the audit reveals every hand after the game anyway.
        assertThat(hand.shown).containsOnlyKeys(button, other);
        assertThat(hand.handNames.get(other)).isEqualTo("Pair of kings");
        assertThat(game.stacks.get(button)).isEqualTo(1_100);
        assertThat(hand.deltas).containsEntry(button, 100L).containsEntry(other, -100L);
        assertThat(game.totals).containsEntry(button, 100L).containsEntry(other, -100L);
    }

    @Test
    void theButtonMovesAndTheGameEndsZeroSumAfterOneFullCycle() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B, C), 100, new Random(2), DECKS, 0);
        long now = 0;
        while (!HoldemGame.DONE.equals(game.phase)) {
            if (HoldemGame.RESULTS.equals(game.phase)) {
                assertThat(game.timeout(RULES, DECKS, game.deadline)).isTrue();
                continue;
            }
            // Everyone folds to the big blind.
            game.act(RULES, turn(game), Action.FOLD, 0, DECKS, ++now);
        }
        assertThat(game.history).hasSize(3);
        assertThat(game.history).extracting(h -> h.buttonId).containsExactlyElementsOf(game.order);
        assertThat(game.totals.values().stream().mapToLong(Long::longValue).sum()).isZero();
        // Each hand the big blind won the small blind's chips.
        assertThat(game.history).allSatisfy(h -> assertThat(h.showdown).isFalse());
        assertThat(game.nextDeadline()).isNull();
    }

    @Test
    void aShortAllInMakesASidePotAndOnlyLetsEarlierActorsCall() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B, C), 100, new Random(3), DECKS, 0);
        // Seat 0 small blind, seat 1 big blind, seat 2 the button acts first.
        UUID sb = game.seats.get(0).userId, bb = game.seats.get(1).userId, btn = game.seats.get(2).userId;
        game.stacks.put(btn, 150L);
        rig(game, "2C 3D 4H 8S 9C", "QS QH", "JS JH", "AS AH");
        game.act(RULES, btn, Action.ALL_IN, 0, DECKS, 1);
        // 150 is only 50 over the 100 big blind: less than a full raise, so it doesn't reopen raising.
        assertThat(game.currentBet).isEqualTo(150);
        game.act(RULES, sb, Action.RAISE, 400, DECKS, 2);
        assertThatThrownBy(() -> game.act(RULES, bb, Action.RAISE, 600, DECKS, 3))
                .isInstanceOf(NotAllowed.class).hasMessageContaining("smallest raise is to 650");
        game.act(RULES, bb, Action.CALL, 0, DECKS, 3);
        // Both still in check it down.
        for (int street = 0; street < 3; street++) {
            game.act(RULES, turn(game), Action.CHECK, 0, DECKS, 4);
            game.act(RULES, turn(game), Action.CHECK, 0, DECKS, 5);
        }
        HoldemGame.Hand hand = game.history.getFirst();
        assertThat(hand.pots).hasSize(2);
        // Main pot: 150 from each of three, won by the all-in aces.
        assertThat(hand.pots.get(0).amount).isEqualTo(450);
        assertThat(hand.pots.get(0).winners).containsExactly(btn);
        // Side pot between the other two: queens beat jacks.
        assertThat(hand.pots.get(1).amount).isEqualTo(500);
        assertThat(hand.pots.get(1).winners).containsExactly(sb);
        // Everyone still in shows, the losing jacks too.
        assertThat(hand.shown).containsOnlyKeys(btn, sb, bb);
    }

    @Test
    void foldingToABetEndsTheHandWithNoShowdownAndAnUncalledBetComesBack() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B), 100, new Random(4), DECKS, 0);
        UUID button = game.buttonId, other = button.equals(A) ? B : A;
        game.act(RULES, button, Action.RAISE, 300, DECKS, 1);
        game.act(RULES, other, Action.FOLD, 0, DECKS, 2);
        HoldemGame.Hand hand = game.history.getFirst();
        assertThat(hand.showdown).isFalse();
        assertThat(hand.shown).isEmpty();
        assertThat(hand.deltas).containsEntry(button, 100L).containsEntry(other, -100L);
    }

    @Test
    void aPreMoveIsPlayedTheMomentTheActionReachesYouAndLastsOneDecision() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B), 100, new Random(4), DECKS, 0);
        UUID button = game.buttonId, other = button.equals(A) ? B : A;
        // Only while it isn't your turn.
        assertThatThrownBy(() -> game.preMove(button, HoldemGame.PreMove.CALL_ANY))
                .isInstanceOf(NotAllowed.class);
        // Call any calls a raise, whatever its size.
        game.preMove(other, HoldemGame.PreMove.CALL_ANY);
        game.act(RULES, button, Action.RAISE, 300, DECKS, 1);
        assertThat(game.street).isEqualTo(HoldemGame.FLOP);
        assertThat(game.stacks.get(other)).isEqualTo(700);
        // It was used up: the big blind decides for itself on the flop.
        assertThat(turn(game)).isEqualTo(other);
        assertThat(game.seatOf(other).preMove).isNull();
        // Check/fold checks when there's nothing to call...
        game.preMove(button, HoldemGame.PreMove.CHECK_FOLD);
        game.act(RULES, other, Action.CHECK, 0, DECKS, 2);
        assertThat(game.street).isEqualTo(HoldemGame.TURN);
        // ...and folds to a bet; a cleared pre-move does nothing.
        game.preMove(button, HoldemGame.PreMove.CALL_ANY);
        game.preMove(button, null);
        game.act(RULES, other, Action.CHECK, 0, DECKS, 3);
        assertThat(turn(game)).isEqualTo(button);
        game.act(RULES, button, Action.CHECK, 0, DECKS, 4);
        game.preMove(button, HoldemGame.PreMove.CHECK_FOLD);
        game.act(RULES, other, Action.RAISE, 500, DECKS, 5);
        assertThat(game.history.getFirst().showdown).isFalse();
        assertThat(game.history.getFirst().deltas).containsEntry(other, 300L);
        // Nobody out of the hand can arm one.
        assertThatThrownBy(() -> game.preMove(button, HoldemGame.PreMove.CHECK_FOLD))
                .isInstanceOf(NotAllowed.class);
    }

    @Test
    void aLapsedTimerChecksWhenItCanAndFoldsWhenItCant() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B), 100, new Random(5), DECKS, 0);
        UUID button = game.buttonId;
        long lapsed = game.deadline;
        assertThat(game.timeout(RULES, DECKS, lapsed - 1)).isFalse();
        // The button owes 50 more: it folds.
        assertThat(game.timeout(RULES, DECKS, lapsed)).isTrue();
        assertThat(game.history.getFirst().deltas.get(button)).isEqualTo(-50L);
        assertThat(game.phase).isEqualTo(HoldemGame.RESULTS);
        assertThat(game.deadline).isEqualTo(lapsed + HoldemRules.HAND_PAUSE_MS);
    }

    @Test
    void everyoneStartsEveryHandOnTheSameStackSoNobodyBustsOut() {
        HoldemGame game = HoldemGame.start(RULES, List.of(A, B, C), 100, new Random(6), DECKS, 0);
        UUID sb = game.seats.get(0).userId, bb = game.seats.get(1).userId, btn = game.seats.get(2).userId;
        rig(game, "2C 3D 4H 8S 9C", "QS QH", "JS JH", "AS AH");
        game.act(RULES, btn, Action.ALL_IN, 0, DECKS, 1);
        game.act(RULES, sb, Action.FOLD, 0, DECKS, 2);
        game.act(RULES, bb, Action.ALL_IN, 0, DECKS, 3);
        // The big blind lost their whole stack; the aces won it and the small blind's 50.
        assertThat(game.stacks.get(bb)).isZero();
        assertThat(game.totals).containsEntry(bb, -1_000L).containsEntry(btn, 1_050L).containsEntry(sb, -50L);

        game.timeout(RULES, DECKS, game.deadline);
        // Hand 2: all three are dealt in, each on a fresh 1,000 (less this hand's blinds), and the results carry on.
        assertThat(game.hand).isEqualTo(2);
        assertThat(game.seats).extracting(s -> s.userId).containsExactlyInAnyOrder(A, B, C);
        assertThat(sum(game)).isEqualTo(3_000);
        assertThat(game.stacks.get(game.buttonId)).isEqualTo(1_000);
        assertThat(game.totals.get(bb)).isEqualTo(-1_000L);
    }

    @Test
    void randomGamesStayZeroSumAndNobodyLosesMoreThanAStackAHand() {
        Random random = new Random(99);
        Action[] actions = Action.values();
        for (int g = 0; g < 500; g++) {
            int count = 2 + random.nextInt(8);
            List<UUID> players = new ArrayList<>();
            for (int i = 0; i < count; i++) players.add(UUID.randomUUID());
            long bigBlind = 10 + random.nextInt(991);
            HoldemGame game = HoldemGame.start(RULES, players, bigBlind, random, DECKS, 0);
            long chips = Math.multiplyExact(game.stack, count);
            long now = 0;
            int guard = 0;
            while (!HoldemGame.DONE.equals(game.phase)) {
                assertThat(guard++).isLessThan(20_000);
                now++;
                if (HoldemGame.RESULTS.equals(game.phase)) {
                    game.timeout(RULES, DECKS, game.deadline);
                    continue;
                }
                if (random.nextInt(10) == 0) {
                    game.timeout(RULES, DECKS, game.deadline);
                    continue;
                }
                UUID actor = game.toActId();
                HoldemGame.Seat seat = game.seatOf(actor);
                Action action = actions[random.nextInt(actions.length)];
                long amount = game.minRaiseTo(seat) + random.nextInt(3) * bigBlind;
                try {
                    game.act(RULES, actor, action, amount, DECKS, now);
                } catch (NotAllowed refused) {
                    game.act(RULES, actor, game.toCall(seat) == 0 ? Action.CHECK : Action.CALL, 0, DECKS, now);
                }
                assertThat(sum(game)).isEqualTo(chips);
                assertThat(game.stacks.values()).allSatisfy(stack -> assertThat(stack).isNotNegative());
            }
            assertThat(game.totals.values().stream().mapToLong(Long::longValue).sum()).isZero();
            for (long total : game.totals.values()) {
                assertThat(total).isGreaterThanOrEqualTo(-HoldemRules.worstCase(bigBlind, count));
            }
            // Nobody busts out, so every hand of the cycle is played.
            assertThat(game.history).hasSize(count);
        }
    }
}
