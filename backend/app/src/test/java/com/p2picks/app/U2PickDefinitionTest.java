package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class U2PickDefinitionTest {
    @Test
    void normalizesCustomTextAndAllowsPassAsAChoice() {
        U2PickDefinition bet = new U2PickDefinition("\uFEFFNext play?  ", List.of(" Pass ", "Run"));
        assertThat(bet.question()).isEqualTo("Next play?");
        assertThat(bet.choices()).containsExactly("Pass", "Run");
        assertThatThrownBy(() -> bet.choices().add("Other")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsBlankDuplicateReservedAndInvalidChoices() {
        for (List<String> choices : List.of(
                List.of("Yes"), List.of("Yes", ""), List.of("Yes", " yes "),
                List.of("Yes", " NO GUESS "), List.of("Yes", "__no_guess__"),
                List.of("Yes", "\u0000"), List.of("1", "2", "3", "4", "5", "6", "7"))) {
            assertThatThrownBy(() -> new U2PickDefinition("Question?", choices))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enforcesQuestionAndChoiceBoundariesInUnicodeCodePoints() {
        assertThat(new U2PickDefinition("😀".repeat(120), List.of("😀".repeat(40), "No")).question())
                .hasSize(240);
        assertThatThrownBy(() -> new U2PickDefinition("😀".repeat(121), List.of("Yes", "No")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new U2PickDefinition("Question?", List.of("😀".repeat(41), "No")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new U2PickDefinition(" \uFEFF ", List.of("Yes", "No")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new U2PickDefinition("\uD800", List.of("Yes", "No")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
