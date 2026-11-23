package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.bets.HoldemService;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HoldemBotTest {
    private static HoldemService.Turn turn(String hole, String board, long toCall, long pot, int made) {
        return new HoldemService.Turn(List.of(hole.split(" ")), board.isBlank() ? List.of() : List.of(board.split(" ")),
                toCall, pot, 200, 10_000, true, 100, made);
    }

    @Test
    void ratesHandsAndNeverMakesAnIllegalMove() {
        assertThat(HoldemBot.strength(turn("AS AH", "", 0, 150, -1))).isEqualTo(2);
        assertThat(HoldemBot.strength(turn("7S 2H", "", 0, 150, -1))).isZero();
        assertThat(HoldemBot.strength(turn("7S 8S", "", 0, 150, -1))).isEqualTo(1);
        assertThat(HoldemBot.strength(turn("7S 2H", "7D 2C 9H", 0, 150, 2))).isEqualTo(2);
        Random random = new Random(1);
        for (int i = 0; i < 500; i++) {
            HoldemBot.Move free = HoldemBot.move(turn("7S 2H", "", 0, 150, -1), random);
            // With nothing to call a bot never folds.
            assertThat(free.action()).isIn("check", "raise", "all_in");
            HoldemBot.Move facing = HoldemBot.move(turn("AS AH", "", 400, 600, -1), random);
            assertThat(facing.action()).isIn("call", "raise", "all_in");
            if (facing.action().equals("raise")) assertThat(facing.amount()).isBetween(200L, 10_000L);
        }
    }
}
