package com.p2picks.app.demo;

import com.p2picks.app.bets.JackblackRules;
import com.p2picks.app.bets.JackblackService;
import java.util.Random;

/**
 * How demo bots play Jackblack: a simplified basic strategy (split aces and eights; double 10 or 11 against a weak
 * dealer card; stand on hard 17+, soft 18+, and 13–16 against a dealer 2–6), and the rules they propose with.
 */
final class JackblackBot {
    private JackblackBot() {}

    static String move(JackblackService.Turn turn) {
        int up = turn.dealerUp();
        if (turn.canSplit()) {
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

    /** Rules for a bot's own game: mostly the defaults, with the odd variation. */
    static JackblackRules rules(Random random) {
        JackblackRules d = JackblackRules.defaults();
        return new JackblackRules(random.nextDouble() < 0.8 ? 1 : 2,
                random.nextDouble() < 0.8, random.nextInt(3), random.nextDouble() < 0.8 ? "3:2" : "6:5",
                d.decisionSec(), d.minPlayers(), d.maxPlayers());
    }
}
