package com.p2picks.app.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotMindTest {
    private static final List<String> SPREAD = List.of("DET -3.5", "GB +3.5");
    private static final List<String> TOTAL = List.of("Over 224.5", "Under 224.5");
    private final Random random = new Random(1);

    @Test
    void pickStylesChooseSidesTheWayTheirNamesSay() {
        BigDecimal homeFavored = new BigDecimal("-3.5");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.FAVORITE, "give_and_take", homeFavored, SPREAD, Map.of()))
                .isEqualTo("DET -3.5");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.UNDERDOG, "give_and_take", homeFavored, SPREAD, Map.of()))
                .isEqualTo("GB +3.5");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.FAVORITE, "give_and_take", new BigDecimal("2.5"),
                List.of("DET +2.5", "GB -2.5"), Map.of())).as("away favored").isEqualTo("GB -2.5");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.HOME, "give_and_take", homeFavored, SPREAD, Map.of()))
                .isEqualTo("DET -3.5");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.FAVORITE, "total_disaster", null, TOTAL, Map.of()))
                .isEqualTo("Over 224.5");
        List<String> u2pick = List.of("Yes", "No", "Maybe");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.CONTRARIAN, "u2pick", null, u2pick,
                Map.of("Yes", 3, "No", 1, "Maybe", 2))).isEqualTo("No");
        assertThat(BotMind.pick(random, DemoProfile.PickStyle.FAVORITE, "u2pick", null, u2pick,
                Map.of("Yes", 3, "No", 1))).as("follows the table").isEqualTo("Yes");
        for (int i = 0; i < 20; i++) {
            assertThat(BotMind.pick(random, DemoProfile.PickStyle.RANDOM, "u2pick", null, u2pick, Map.of())).isIn(u2pick);
        }
    }

    @Test
    void onlineOnlyInActiveHoursAndStableWithinASlot() {
        UUID bot = UUID.randomUUID();
        DemoProfile always = DemoProfile.DEFAULT.with(p -> p.onlineChance(1)
                .activeHours(LocalTime.of(10, 0), LocalTime.of(1, 0)));
        assertThat(BotMind.online(bot, DemoProfile.DEFAULT.with(p -> p.onlineChance(1)),
                Instant.parse("2026-11-15T09:00:00Z"))).as("the default is all day").isTrue();
        Instant fourAmNewYork = Instant.parse("2026-11-15T09:00:00Z");
        Instant eightPmNewYork = Instant.parse("2026-11-15T01:00:00Z");
        Instant halfPastMidnight = Instant.parse("2026-11-15T05:30:00Z");
        assertThat(BotMind.online(bot, always, fourAmNewYork)).as("outside 10:00–01:00").isFalse();
        assertThat(BotMind.online(bot, always, eightPmNewYork)).isTrue();
        assertThat(BotMind.online(bot, always, halfPastMidnight)).as("wraps past midnight").isTrue();
        assertThat(BotMind.online(bot, DemoProfile.DEFAULT.with(p -> p.onlineChance(0)), eightPmNewYork)).isFalse();
        DemoProfile sometimes = DemoProfile.DEFAULT;
        boolean first = BotMind.online(bot, sometimes, eightPmNewYork);
        assertThat(BotMind.online(bot, sometimes, eightPmNewYork.plusSeconds(60))).as("same slot").isEqualTo(first);
        DemoProfile dayOnly = always.with(p -> p.activeHours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        assertThat(BotMind.online(bot, dayOnly, eightPmNewYork)).isFalse();
    }

    @Test
    void delaysRespectTheLatestMomentAndWagersTheRules() {
        DemoProfile slow = DemoProfile.DEFAULT.with(p -> p.reactionSeconds(100, 100));
        assertThat(BotMind.delay(random, slow, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
        assertThat(BotMind.delay(random, slow, null)).isEqualTo(Duration.ofSeconds(100));
        for (DemoRoster.DemoUser user : DemoRoster.users()) {
            long wager = BotMind.wager(random, user.profile());
            assertThat(wager).isBetween(10L, 1_000L);
            assertThat(wager % 25).isZero();
        }
        assertThat(BotMind.happens(random, 0, Duration.ofSeconds(5))).isFalse();
        assertThat(BotMind.happens(random, 3_600_000, Duration.ofSeconds(5))).isTrue();
    }
}
