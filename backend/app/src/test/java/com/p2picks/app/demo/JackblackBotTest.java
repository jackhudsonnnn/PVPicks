package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.bets.JackblackService;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JackblackBotTest {
    private static JackblackService.Turn turn(int total, boolean soft, int up, boolean canDouble, boolean canSplit,
            String... cards) {
        return new JackblackService.Turn(List.of(cards), total, soft, up, canDouble, canSplit);
    }

    @Test
    void playsASimpleBasicStrategy() {
        assertThat(JackblackBot.move(turn(16, false, 10, true, true, "8S", "8H"))).isEqualTo("split");
        assertThat(JackblackBot.move(turn(12, true, 6, true, true, "AS", "AH"))).isEqualTo("split");
        assertThat(JackblackBot.move(turn(11, false, 10, true, false, "5S", "6H"))).isEqualTo("double");
        assertThat(JackblackBot.move(turn(10, false, 10, true, false, "4S", "6H"))).isEqualTo("hit");
        assertThat(JackblackBot.move(turn(11, false, 9, false, false, "5S", "6H"))).isEqualTo("hit");
        assertThat(JackblackBot.move(turn(17, true, 9, true, false, "AS", "6H"))).isEqualTo("hit");
        assertThat(JackblackBot.move(turn(18, true, 9, true, false, "AS", "7H"))).isEqualTo("stand");
        assertThat(JackblackBot.move(turn(15, false, 5, false, false, "TS", "5H"))).isEqualTo("stand");
        assertThat(JackblackBot.move(turn(15, false, 7, false, false, "TS", "5H"))).isEqualTo("hit");
        assertThat(JackblackBot.move(turn(17, false, 11, false, false, "TS", "7H"))).isEqualTo("stand");
    }

    @Test
    void proposesRulesInsideTheirLimits() {
        Random random = new Random(5);
        for (int i = 0; i < 200; i++) assertThat(JackblackBot.rules(random)).isNotNull();
    }
}
