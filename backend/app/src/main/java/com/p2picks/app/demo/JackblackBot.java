package com.p2picks.app.demo;

import com.p2picks.app.bets.BlackjackRules;
import com.p2picks.app.bets.BlackjackService;
import java.util.Random;

/**
 * How demo bots play Blackjack: a simplified basic strategy (no insurance; split aces and eights; double 10 or 11
 * against a weak dealer card; stand on hard 17+, soft 18+, and 13–16 against a dealer 2–6), what they sit down
 * with, and the house rules they deal with.
 */
final class BlackjackBot {
    private BlackjackBot() {}

    static String move(BlackjackService.Turn turn) {
        if (turn.phase().equals("insurance")) return "no_insurance";
        int up = turn.dealerUp();
        if (turn.canSplit() && turn.cards().size() == 2) {
            char rank = turn.cards().getFirst().charAt(0);
            if (rank == 'A' || rank == '8') return "split";
        }
        int total = turn.total();
        if (turn.canDouble() && !turn.soft() && (total == 11 || (total == 10 && up < 10))) return "double";
        if (turn.soft()) return total >= 18 ? "stand" : "hit";
        if (total >= 17) return "stand";
        if (total >= 13) return up <= 6 ? "stand" : "hit";
        if (total == 12) return up >= 4 && up <= 6 ? "stand" : "hit";
        return "hit";
    }

    /** A bet inside the dealer's limits, in 0.10 steps, leaning toward the minimum. */
    static long bet(Random random, long minCents, long maxCents) {
        long steps = (maxCents - minCents) / 10;
        double lean = random.nextDouble() * random.nextDouble();
        return minCents + Math.round(lean * steps) * 10;
    }

    /** House rules for a bot's own hand: mostly the defaults, with a shorter join window. */
    static BlackjackRules rules(Random random) {
        BlackjackRules d = BlackjackRules.defaults();
        long min = random.nextBoolean() ? 50 : 100;
        long max = random.nextBoolean() ? 300 : 500;
        return new BlackjackRules(1 + random.nextInt(6), min, max, random.nextInt(4), random.nextDouble() < 0.3,
                random.nextDouble() < 0.8 ? "3:2" : "6:5", 4 + random.nextInt(4), d.doubleOn(), d.doubleAfterSplit(),
                random.nextDouble() < 0.3, random.nextDouble() < 0.7 ? "together" : "seats", 45 + random.nextInt(76),
                d.decisionSec());
    }
}
