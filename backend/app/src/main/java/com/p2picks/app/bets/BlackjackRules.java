package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The house rules a Blackjack dealer sets when proposing (domain rules, "Blackjack"). Every number has a minimum and
 * maximum; anything left out takes its default. Bets are in cents, inside the same 0.10–10 point range as other bets.
 */
public record BlackjackRules(int decks, long minBetCents, long maxBetCents, int maxSplits, boolean dealerHitsSoft17,
        String blackjackPays, int seats, String doubleOn, boolean doubleAfterSplit, boolean insurance,
        String turnOrder, int joinSec, int decisionSec) {

    static final int MIN_DECKS = 1, MAX_DECKS = 8, DEFAULT_DECKS = 6;
    static final long MIN_BET = 10, MAX_BET = 1000, DEFAULT_MIN_BET = 100, DEFAULT_MAX_BET = 1000;
    static final int MIN_SPLITS = 0, MAX_SPLITS = 5, DEFAULT_SPLITS = 3;
    static final int MIN_SEATS = 1, MAX_SEATS = 7, DEFAULT_SEATS = 7;
    static final int MIN_JOIN_SEC = 30, MAX_JOIN_SEC = 600, DEFAULT_JOIN_SEC = 120;
    static final int MIN_DECISION_SEC = 10, MAX_DECISION_SEC = 120, DEFAULT_DECISION_SEC = 30;
    static final Set<String> PAYS = Set.of("3:2", "6:5");
    static final Set<String> DOUBLE_ON = Set.of("any", "9-11");
    static final Set<String> TURN_ORDERS = Set.of("together", "seats");
    private static final Set<String> FIELDS = Set.of("decks", "minBetCents", "maxBetCents", "maxSplits",
            "dealerHitsSoft17", "blackjackPays", "seats", "doubleOn", "doubleAfterSplit", "insurance", "turnOrder",
            "joinSec", "decisionSec");

    public BlackjackRules {
        range("decks", decks, MIN_DECKS, MAX_DECKS);
        range("minBetCents", minBetCents, MIN_BET, MAX_BET);
        range("maxBetCents", maxBetCents, minBetCents, MAX_BET);
        range("maxSplits", maxSplits, MIN_SPLITS, MAX_SPLITS);
        range("seats", seats, MIN_SEATS, MAX_SEATS);
        range("joinSec", joinSec, MIN_JOIN_SEC, MAX_JOIN_SEC);
        range("decisionSec", decisionSec, MIN_DECISION_SEC, MAX_DECISION_SEC);
        if (!PAYS.contains(blackjackPays)) throw new IllegalArgumentException("blackjackPays must be 3:2 or 6:5");
        if (!DOUBLE_ON.contains(doubleOn)) throw new IllegalArgumentException("doubleOn must be any or 9-11");
        if (!TURN_ORDERS.contains(turnOrder)) throw new IllegalArgumentException("turnOrder must be together or seats");
    }

    public static BlackjackRules defaults() {
        return new BlackjackRules(DEFAULT_DECKS, DEFAULT_MIN_BET, DEFAULT_MAX_BET, DEFAULT_SPLITS, false, "3:2",
                DEFAULT_SEATS, "any", true, false, "together", DEFAULT_JOIN_SEC, DEFAULT_DECISION_SEC);
    }

    /** Reads a proposal's rules; missing fields take their defaults, unknown or invalid ones are refused. */
    static BlackjackRules parse(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) return defaults();
        if (!input.isObject()) throw new IllegalArgumentException("rules must be an object");
        input.fieldNames().forEachRemaining(name -> {
            if (!FIELDS.contains(name)) throw new IllegalArgumentException("Unknown rule " + name);
        });
        BlackjackRules d = defaults();
        return new BlackjackRules(integer(input, "decks", d.decks()), whole(input, "minBetCents", d.minBetCents()),
                whole(input, "maxBetCents", d.maxBetCents()), integer(input, "maxSplits", d.maxSplits()),
                flag(input, "dealerHitsSoft17", d.dealerHitsSoft17()), text(input, "blackjackPays", d.blackjackPays()),
                integer(input, "seats", d.seats()), text(input, "doubleOn", d.doubleOn()),
                flag(input, "doubleAfterSplit", d.doubleAfterSplit()), flag(input, "insurance", d.insurance()),
                text(input, "turnOrder", d.turnOrder()), integer(input, "joinSec", d.joinSec()),
                integer(input, "decisionSec", d.decisionSec()));
    }

    /** Stored on the bet as its config and returned to clients. */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decks", decks);
        map.put("minBetCents", minBetCents);
        map.put("maxBetCents", maxBetCents);
        map.put("maxSplits", maxSplits);
        map.put("dealerHitsSoft17", dealerHitsSoft17 ? "yes" : "no");
        map.put("blackjackPays", blackjackPays);
        map.put("seats", seats);
        map.put("doubleOn", doubleOn);
        map.put("doubleAfterSplit", doubleAfterSplit ? "yes" : "no");
        map.put("insurance", insurance ? "yes" : "no");
        map.put("turnOrder", turnOrder);
        map.put("joinSec", joinSec);
        map.put("decisionSec", decisionSec);
        return map;
    }

    /** Reads rules back from a stored config (booleans are stored as yes/no, like other bet configs). */
    static BlackjackRules fromConfig(JsonNode config) {
        return new BlackjackRules(config.path("decks").asInt(), config.path("minBetCents").asLong(),
                config.path("maxBetCents").asLong(), config.path("maxSplits").asInt(),
                "yes".equals(config.path("dealerHitsSoft17").asText()), config.path("blackjackPays").asText(),
                config.path("seats").asInt(), config.path("doubleOn").asText(),
                "yes".equals(config.path("doubleAfterSplit").asText()),
                "yes".equals(config.path("insurance").asText()), config.path("turnOrder").asText(),
                config.path("joinSec").asInt(), config.path("decisionSec").asInt());
    }

    /**
     * The most a dealer can lose to one seat that bet {@code betCents}: every split taken and every hand doubled and
     * won. A natural pays at most 3:2 and insurance only pays when the main hand loses, so neither exceeds this.
     */
    static long worstCase(long betCents, int maxSplits) {
        return Math.multiplyExact(betCents, 2L * (maxSplits + 1));
    }

    /** The dealer's worst case with every seat taken at the maximum bet. */
    long worstCaseFullTable() {
        return Math.multiplyExact(worstCase(maxBetCents, maxSplits), seats);
    }

    private static void range(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }

    private static int integer(JsonNode input, String name, int fallback) {
        JsonNode value = input.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
        return value.intValue();
    }

    private static long whole(JsonNode input, String name, long fallback) {
        JsonNode value = input.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(name + " must be a whole number of cents");
        }
        return value.longValue();
    }

    private static boolean flag(JsonNode input, String name, boolean fallback) {
        JsonNode value = input.get(name);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(name + " must be true or false");
        return value.booleanValue();
    }

    private static String text(JsonNode input, String name, String fallback) {
        JsonNode value = input.get(name);
        if (value == null) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be text");
        return value.textValue();
    }
}
