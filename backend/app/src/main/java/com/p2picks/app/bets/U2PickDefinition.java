package com.p2picks.app.bets;

import com.p2picks.app.common.PostgresText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

record U2PickDefinition(String question, List<String> choices) {
    U2PickDefinition {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(choices, "choices");
        question = PostgresText.trim(question);
        if (question.isEmpty() || !PostgresText.valid(question)
                || question.codePointCount(0, question.length()) > 120) {
            throw new IllegalArgumentException("Invalid U2Pick question");
        }
        if (choices.size() < 2 || choices.size() > 6) {
            throw new IllegalArgumentException("U2Pick requires two to six choices");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : choices) {
            if (raw == null) throw new IllegalArgumentException("Invalid U2Pick choice");
            String choice = PostgresText.trim(raw);
            String key = choice.toLowerCase(Locale.ROOT);
            if (choice.isEmpty() || !PostgresText.valid(choice)
                    || choice.codePointCount(0, choice.length()) > 40
                    || key.equals("no guess") || key.equals("__no_guess__") || !seen.add(key)) {
                throw new IllegalArgumentException("Invalid U2Pick choice");
            }
            normalized.add(choice);
        }
        choices = List.copyOf(normalized);
    }
}
