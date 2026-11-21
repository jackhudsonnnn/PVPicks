package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.bets.BlackjackService;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BlackjackBotTest {
    private static BlackjackService.Turn play(List<String> cards, int total, boolean soft, int up, boolean dbl,
            boolean split) {
        return new BlackjackService.Turn("play", cards, total, soft, up, dbl, split);
    }

    @Test
    void playsSimpleBasicStrategy() {
        assertThat(BlackjackBot.move(new BlackjackService.Turn("insurance", List.of("TS", "9H"), 19, false, 11,
                false, false))).isEqualTo("no_insurance");
        assertThat(BlackjackBot.move(play(List.of("8S", "8H"), 16, false, 10, true, true))).isEqualTo("split");
        assertThat(BlackjackBot.move(play(List.of("6S", "5H"), 11, false, 10, true, false))).isEqualTo("double");
        assertThat(BlackjackBot.move(play(List.of("6S", "4H"), 10, false, 10, true, false))).isEqualTo("hit");
        assertThat(BlackjackBot.move(play(List.of("TS", "6H"), 16, false, 5, true, false))).isEqualTo("stand");
        assertThat(BlackjackBot.move(play(List.of("TS", "6H"), 16, false, 9, true, false))).isEqualTo("hit");
        assertThat(BlackjackBot.move(play(List.of("AS", "6H"), 17, true, 5, false, false))).isEqualTo("hit");
        assertThat(BlackjackBot.move(play(List.of("TS", "7H"), 17, false, 11, false, false))).isEqualTo("stand");
    }

    @Test
    void betsAndRulesStayInsideTheLimits() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            long bet = BlackjackBot.bet(random, 50, 500);
            assertThat(bet).isBetween(50L, 500L);
            assertThat(bet % 10).isZero();
            BlackjackBot.rules(random);
        }
    }
}
