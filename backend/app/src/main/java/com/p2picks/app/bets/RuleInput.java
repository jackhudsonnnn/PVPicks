package com.p2picks.app.bets;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Reads a card game proposal's {@code rules} object: unknown fields are refused, missing ones take their default, and
 * every value must have the right type and stay in its range. Messages name the field, for the 400.
 */
final class RuleInput {
    private final JsonNode input;

    private RuleInput(JsonNode input) {
        this.input = input;
    }

    /** Null or missing rules are an empty object (all defaults); anything not an object, or an unknown field, fails. */
    static RuleInput of(JsonNode input, Set<String> fields) {
        if (input == null || input.isNull() || input.isMissingNode()) return new RuleInput(null);
        if (!input.isObject()) throw new IllegalArgumentException("rules must be an object");
        input.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown rule " + name);
        });
        return new RuleInput(input);
    }

    int integer(String name, int fallback) {
        JsonNode value = input == null ? null : input.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
        return value.intValue();
    }

    boolean flag(String name, boolean fallback) {
        JsonNode value = input == null ? null : input.get(name);
        if (value == null) return fallback;
        if (!value.isBoolean()) throw new IllegalArgumentException(name + " must be true or false");
        return value.booleanValue();
    }

    String text(String name, String fallback) {
        JsonNode value = input == null ? null : input.get(name);
        if (value == null) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(name + " must be text");
        return value.textValue();
    }

    /** Throws unless {@code min <= value <= max}. */
    static void range(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }
}
