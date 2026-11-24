package com.p2picks.app.demo;

import com.p2picks.app.bets.HoldemRules;
import com.p2picks.app.bets.HoldemService;
import java.util.List;
import java.util.Random;

/**
 * How demo bots play Hold'em: a simple, readable strategy, not a solver. Before the flop they rate their two cards
 * (pairs, two high cards, suited connectors); after it, the hand they've made. Strong hands bet and raise, middling
 * ones call a modest bet, weak ones check or fold, with the odd bluff so they aren't predictable.
 */
final class HoldemBot {
    private HoldemBot() {}

    record Move(String action, long amount) {}

    private static final String RANKS = "23456789TJQKA";

    /** 0 weak, 1 middling, 2 strong. */
    static int strength(HoldemService.Turn turn) {
        if (turn.madeHand() >= 0) {
            // Two pair or better is strong; a pair is middling.
            return turn.madeHand() >= 2 ? 2 : turn.madeHand() == 1 ? 1 : 0;
        }
        List<String> hole = turn.hole();
        int a = RANKS.indexOf(hole.get(0).charAt(0));
        int b = RANKS.indexOf(hole.get(1).charAt(0));
        boolean suited = hole.get(0).charAt(1) == hole.get(1).charAt(1);
        if (a == b) return a >= 8 ? 2 : 1;
        int high = Math.max(a, b);
        int low = Math.min(a, b);
        if (high >= 11 && low >= 9) return 2;
        if (high >= 9 || (suited && high - low == 1 && low >= 4)) return 1;
        return 0;
    }

    static Move move(HoldemService.Turn turn, Random random) {
        int strength = strength(turn);
        long pot = Math.max(turn.pot(), turn.bigBlind());
        boolean bluff = random.nextDouble() < 0.08;
        if (turn.canRaise() && (strength == 2 && random.nextDouble() < 0.6 || bluff)) {
            long size = turn.toCall() + pot / 2 + random.nextInt(2) * pot / 2;
            long to = Math.max(turn.minRaiseTo(), Math.min(turn.maxRaiseTo(), turn.minRaiseTo() + size));
            return to >= turn.maxRaiseTo() ? new Move("all_in", 0) : new Move("raise", to);
        }
        if (turn.toCall() == 0) return new Move("check", 0);
        boolean cheap = turn.toCall() <= pot / 2;
        if (strength == 2 || (strength == 1 && (cheap || random.nextDouble() < 0.3))
                || (cheap && random.nextDouble() < 0.2)) {
            return new Move("call", 0);
        }
        return new Move("fold", 0);
    }

    /** Rules for a bot's own game: mostly the default timer. */
    static HoldemRules rules(Random random) {
        return new HoldemRules(random.nextDouble() < 0.7 ? HoldemRules.defaults().decisionSec() : 20,
                HoldemRules.defaults().minPlayers(), HoldemRules.defaults().maxPlayers());
    }
}
