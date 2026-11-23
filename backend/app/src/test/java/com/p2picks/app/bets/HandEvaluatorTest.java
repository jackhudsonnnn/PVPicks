package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandEvaluatorTest {
    private static HandEvaluator.Hand best(String cards) {
        return HandEvaluator.best(HandEvaluator.cards(cards));
    }

    @Test
    void namesEveryCategoryAndRanksThemInOrder() {
        List<HandEvaluator.Hand> ladder = List.of(
                best("AS KD 9C 7H 3S 2D 4C"),
                best("AS AD 9C 7H 3S 2D 5C"),
                best("AS AD 9C 9H 3S 2D 5C"),
                best("AS AD AC 9H 3S 2D 6C"),
                best("AS 2D 3C 4H 5S 9D KC"),
                best("2H 7H 9H JH KH AS AD"),
                best("AS AD AC 9H 9S 2D 6C"),
                best("AS AD AC AH 9S 2D 6C"),
                best("9H TH JH QH KH 2S 2D"),
                best("TS JS QS KS AS 2D 2C"));
        assertThat(ladder).extracting(HandEvaluator.Hand::name).containsExactly(
                "Ace high", "Pair of aces", "Two pair, aces and nines", "Three aces", "Straight, five high",
                "Flush, king high", "Full house, aces over nines", "Four aces", "Straight flush, king high",
                "Royal flush");
        for (int i = 1; i < ladder.size(); i++) {
            assertThat(ladder.get(i).score()).isGreaterThan(ladder.get(i - 1).score());
        }
    }

    @Test
    void kickersBreakTiesAndTheBoardCanPlayForBoth() {
        // Same pair of aces, king kicker beats queen kicker.
        assertThat(best("AS AD KC 7H 3S 2D 9C").score()).isGreaterThan(best("AH AC QC 7D 3S 2D 9C").score());
        // The board's straight plays for both: a split.
        assertThat(best("2S 3D 5C 6H 7S 8D 9C").score()).isEqualTo(best("2H 3C 5C 6H 7S 8D 9C").score());
        // The wheel is the lowest straight; six high beats it.
        assertThat(best("6S 2D 3C 4H 5S KD KC").score()).isGreaterThan(best("AS 2D 3C 4H 5S KD 9C").score());
    }
}
