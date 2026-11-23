package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hold'em's settings and fixed terms (domain rules, "Hold'em"). The proposer sets the rotations (how many times the
 * button goes round) and the turn timer; the big blind is the bet's wager (the shared slider) and everything else is
 * fixed: no-limit, small blind half the big blind, a buy-in of 100 big blinds, one rebuy a game.
 */
public record HoldemRules(int rotations, int decisionSec, int minPlayers, int maxPlayers) {
    static final int MIN_ROTATIONS = 1, MAX_ROTATIONS = 3, DEFAULT_ROTATIONS = 1;
    static final int MIN_DECISION_SEC = 10, MAX_DECISION_SEC = 60, DEFAULT_DECISION_SEC = 30;
    /** The range the proposer can set for players at one game (fewer than their minimum at the deal washes). */
    static final int MIN_PLAYERS = 2, MAX_PLAYERS = 9;
    static final int BUY_IN_BIG_BLINDS = 100;
    /** Buy-ins anyone can spend in a game: the first and one rebuy. */
    static final int MAX_BUY_INS = 2;
    /** A 4fun game has no points at stake, but poker needs blinds: it plays with chips worth nothing, 1.00 a blind. */
    static final long FUN_BIG_BLIND = 100;
    /** How long a busted player has to take their one rebuy (owner decision, 2026-11-22). */
    static final long REBUY_MS = 15_000;
    /** How long a finished hand stays up (who won, shown hands) before the next deal. */
    static final long HAND_PAUSE_MS = 5_000;
    private static final Set<String> FIELDS = Set.of("rotations", "decisionSec", "minPlayers", "maxPlayers");

    public HoldemRules {
        RuleInput.range("rotations", rotations, MIN_ROTATIONS, MAX_ROTATIONS);
        RuleInput.range("decisionSec", decisionSec, MIN_DECISION_SEC, MAX_DECISION_SEC);
        RuleInput.range("minPlayers", minPlayers, MIN_PLAYERS, MAX_PLAYERS);
        RuleInput.range("maxPlayers", maxPlayers, minPlayers, MAX_PLAYERS);
    }

    public static HoldemRules defaults() {
        return new HoldemRules(DEFAULT_ROTATIONS, DEFAULT_DECISION_SEC, MIN_PLAYERS, MAX_PLAYERS);
    }

    /** Reads a proposal's settings; missing fields take their defaults, unknown or invalid ones are refused. */
    static HoldemRules parse(JsonNode input) {
        RuleInput rules = RuleInput.of(input, FIELDS);
        return new HoldemRules(rules.integer("rotations", DEFAULT_ROTATIONS),
                rules.integer("decisionSec", DEFAULT_DECISION_SEC), rules.integer("minPlayers", MIN_PLAYERS),
                rules.integer("maxPlayers", MAX_PLAYERS));
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rotations", rotations);
        map.put("decisionSec", decisionSec);
        map.put("minPlayers", minPlayers);
        map.put("maxPlayers", maxPlayers);
        return map;
    }

    static HoldemRules fromConfig(JsonNode config) {
        return new HoldemRules(config.path("rotations").asInt(), config.path("decisionSec").asInt(),
                config.path("minPlayers").asInt(), config.path("maxPlayers").asInt());
    }

    /** The big blind chips play with: the wager, or play chips in a 4fun game. */
    static long bigBlind(long wagerCents, boolean forFun) {
        return forFun ? FUN_BIG_BLIND : wagerCents;
    }

    static long buyIn(long bigBlind) {
        return Math.multiplyExact(bigBlind, BUY_IN_BIG_BLINDS);
    }

    /** The most a player can lose: both buy-ins (held against their points limit while the game is on). */
    static long worstCase(long wagerCents) {
        return Math.multiplyExact(buyIn(wagerCents), MAX_BUY_INS);
    }
}
