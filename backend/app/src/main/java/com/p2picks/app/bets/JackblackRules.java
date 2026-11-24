package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Jackblack's settings (domain rules, "Jackblack"), each held to a minimum and maximum; anything left out takes its
 * default. The ante and the join window are the bet's wager and pick window, shared with every mode.
 */
public record JackblackRules(boolean doubling, int maxSplits, String blackjackPays, int decisionSec, int minPlayers,
        int maxPlayers) {

    static final int MIN_SPLITS = 0, MAX_SPLITS = 3, DEFAULT_SPLITS = 1;
    static final int MIN_DECISION_SEC = 10, MAX_DECISION_SEC = 60, DEFAULT_DECISION_SEC = 30;
    /**
     * The range the proposer can set for players at one game, the dealer included (owner decision, 2026-11-23):
     * fewer than their minimum at the deal washes; more than seven would make the game drag.
     */
    static final int MIN_PLAYERS = 2, MAX_PLAYERS = 7;
    static final Set<String> PAYS = Set.of("3:2", "6:5");
    private static final Set<String> FIELDS = Set.of("doubling", "maxSplits", "blackjackPays",
            "decisionSec", "minPlayers", "maxPlayers");

    public JackblackRules {
        RuleInput.range("maxSplits", maxSplits, MIN_SPLITS, MAX_SPLITS);
        RuleInput.range("decisionSec", decisionSec, MIN_DECISION_SEC, MAX_DECISION_SEC);
        if (!PAYS.contains(blackjackPays)) throw new IllegalArgumentException("blackjackPays must be 3:2 or 6:5");
        RuleInput.range("minPlayers", minPlayers, MIN_PLAYERS, MAX_PLAYERS);
        RuleInput.range("maxPlayers", maxPlayers, minPlayers, MAX_PLAYERS);
    }

    public static JackblackRules defaults() {
        return new JackblackRules(true, DEFAULT_SPLITS, "3:2", DEFAULT_DECISION_SEC, MIN_PLAYERS,
                MAX_PLAYERS);
    }

    /** Reads a proposal's settings; missing fields take their defaults, unknown or invalid ones are refused. */
    static JackblackRules parse(JsonNode input) {
        RuleInput rules = RuleInput.of(input, FIELDS);
        JackblackRules d = defaults();
        return new JackblackRules(rules.flag("doubling", d.doubling()),
                rules.integer("maxSplits", d.maxSplits()), rules.text("blackjackPays", d.blackjackPays()),
                rules.integer("decisionSec", d.decisionSec()), rules.integer("minPlayers", d.minPlayers()),
                rules.integer("maxPlayers", d.maxPlayers()));
    }

    /** Stored on the bet as its config (booleans as yes/no, like other bet configs). */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("doubling", doubling ? "yes" : "no");
        map.put("maxSplits", maxSplits);
        map.put("blackjackPays", blackjackPays);
        map.put("decisionSec", decisionSec);
        map.put("minPlayers", minPlayers);
        map.put("maxPlayers", maxPlayers);
        return map;
    }

    static JackblackRules fromConfig(JsonNode config) {
        return new JackblackRules("yes".equals(config.path("doubling").asText()), config.path("maxSplits").asInt(),
                config.path("blackjackPays").asText(), config.path("decisionSec").asInt(),
                config.path("minPlayers").asInt(), config.path("maxPlayers").asInt());
    }

    /** The most one hand can put at risk: every split taken and every hand doubled. */
    long handWorstCase(long anteCents) {
        return Math.multiplyExact(anteCents, (long) (doubling ? 2 : 1) * (maxSplits + 1));
    }

    /** The most a dealer can pay one seat: its worst case won, or a natural (never split or doubled). */
    long dealerWorstCase(long anteCents) {
        long natural = blackjackPays.equals("3:2") ? anteCents * 3 / 2 : anteCents * 6 / 5;
        return Math.max(handWorstCase(anteCents), natural);
    }

    /**
     * The most a player can lose over the whole ticket (owner: assume the worst): every round they sit, at the
     * hand's worst case, and the round they deal, paying everyone else the most a seat can win.
     */
    long worstCase(long anteCents, int players) {
        long perSeat = Math.addExact(handWorstCase(anteCents), dealerWorstCase(anteCents));
        return Math.multiplyExact(perSeat, players - 1);
    }
}
