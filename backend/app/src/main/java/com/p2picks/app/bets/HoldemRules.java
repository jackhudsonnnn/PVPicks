package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hold'em's settings and fixed terms (domain rules, "Hold'em"). The proposer sets the turn timer and the players
 * range; the game is one hand per player, the button going round once (owner decision, 2026-11-24: "Run it back" plays
 * another); the big blind is the bet's wager (the shared slider) and everything else is
 * fixed: no-limit, small blind half the big blind, and every hand starts everyone on the same stack of 10 big blinds
 * (owner decision, 2026-11-26: chips that carried between hands gave some seats an edge).
 */
public record HoldemRules(int decisionSec, int minPlayers, int maxPlayers) {
    static final int MIN_DECISION_SEC = 10, MAX_DECISION_SEC = 60, DEFAULT_DECISION_SEC = 30;
    /** The range the proposer can set for players at one game (fewer than their minimum at the deal washes). */
    static final int MIN_PLAYERS = 2, MAX_PLAYERS = 9;
    /** Everyone's stack at the start of every hand, in big blinds. */
    static final int STACK_BIG_BLINDS = 10;
    /** A 4fun game has no points at stake, but poker needs blinds: it plays with chips worth nothing, 1.00 a blind. */
    static final long FUN_BIG_BLIND = 100;
    /** How long a finished hand stays up (who won, shown hands) before the next deal. */
    static final long HAND_PAUSE_MS = 5_000;
    private static final Set<String> FIELDS = Set.of("decisionSec", "minPlayers", "maxPlayers");

    public HoldemRules {
        RuleInput.range("decisionSec", decisionSec, MIN_DECISION_SEC, MAX_DECISION_SEC);
        RuleInput.range("minPlayers", minPlayers, MIN_PLAYERS, MAX_PLAYERS);
        RuleInput.range("maxPlayers", maxPlayers, minPlayers, MAX_PLAYERS);
    }

    public static HoldemRules defaults() {
        return new HoldemRules(DEFAULT_DECISION_SEC, MIN_PLAYERS, MAX_PLAYERS);
    }

    /** Reads a proposal's settings; missing fields take their defaults, unknown or invalid ones are refused. */
    static HoldemRules parse(JsonNode input) {
        RuleInput rules = RuleInput.of(input, FIELDS);
        return new HoldemRules(rules.integer("decisionSec", DEFAULT_DECISION_SEC), rules.integer("minPlayers", MIN_PLAYERS),
                rules.integer("maxPlayers", MAX_PLAYERS));
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decisionSec", decisionSec);
        map.put("minPlayers", minPlayers);
        map.put("maxPlayers", maxPlayers);
        return map;
    }

    static HoldemRules fromConfig(JsonNode config) {
        return new HoldemRules(config.path("decisionSec").asInt(),
                config.path("minPlayers").asInt(), config.path("maxPlayers").asInt());
    }

    /** The big blind chips play with: the wager, or play chips in a 4fun game. */
    static long bigBlind(long wagerCents, boolean forFun) {
        return forFun ? FUN_BIG_BLIND : wagerCents;
    }

    /** Everyone's stack at the start of each hand. */
    static long stack(long bigBlind) {
        return Math.multiplyExact(bigBlind, STACK_BIG_BLINDS);
    }

    /**
     * The most a player can lose (held against their points limit while the game is on): their whole stack in every
     * hand, one hand per player.
     */
    static long worstCase(long wagerCents, int players) {
        return Math.multiplyExact(stack(wagerCents), players);
    }
}
