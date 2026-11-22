package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SportsOutcomeTest {
    private static final Instant FINAL_AT = Instant.parse("2026-11-14T04:00:00Z");
    private static final Instant AFTER_GRACE = FINAL_AT.plus(SportsOutcome.GRACE);

    private static GameLookup.Game game(GameStatus status, String state, int home, int away, List<Integer> homePeriods,
            List<Integer> awayPeriods) {
        return new GameLookup.Game("NBA-1", League.NBA, status, state, FINAL_AT.minusSeconds(9000),
                status == GameStatus.FINAL ? FINAL_AT : null, new GameLookup.Team("BOS", home, homePeriods),
                new GameLookup.Team("NYK", away, awayPeriods));
    }

    private static GameLookup.Game finished(int home, int away) {
        return game(GameStatus.FINAL, "final", home, away, List.of(), List.of());
    }

    private static SportsOutcome.Terms total(String line, boolean overtime) {
        BigDecimal value = new BigDecimal(line);
        return new SportsOutcome.Terms("total_disaster", value, overtime,
                SportsProposalService.terms("total_disaster", value, "BOS", "NYK").choices());
    }

    private static SportsOutcome.Terms spread(String line) {
        BigDecimal value = new BigDecimal(line);
        return new SportsOutcome.Terms("give_and_take", value, true,
                SportsProposalService.terms("give_and_take", value, "BOS", "NYK").choices());
    }

    @Test
    void totalsSettleOverUnderAndWashOnAPush() {
        assertThat(SportsOutcome.decide(total("224.5", true), finished(120, 110), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Winner("Over 224.5"));
        assertThat(SportsOutcome.decide(total("224.5", true), finished(100, 110), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Winner("Under 224.5"));
        assertThat(SportsOutcome.decide(total("210", true), finished(100, 110), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Wash("Push"));
    }

    @Test
    void spreadsAreTheHomeTeamsHandicap() {
        // BOS -5.5: wins by 6 covers, wins by 5 doesn't.
        assertThat(SportsOutcome.decide(spread("-5.5"), finished(106, 100), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Winner("BOS -5.5"));
        assertThat(SportsOutcome.decide(spread("-5.5"), finished(105, 100), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Winner("NYK +5.5"));
        assertThat(SportsOutcome.decide(spread("+3"), finished(97, 100), null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Wash("Push"));
        assertThat(SportsProposalService.terms("give_and_take", new BigDecimal("0"), "BOS", "NYK").choices())
                .containsExactly("BOS 0", "NYK 0");
    }

    @Test
    void overtimeOffUsesRegulationScores() {
        GameLookup.Game overtime = game(GameStatus.FINAL, "final", 120, 112, List.of(25, 25, 25, 25, 20),
                List.of(30, 20, 25, 25, 12));
        assertThat(SportsOutcome.decide(total("205.5", false), overtime, null, AFTER_GRACE))
                .as("200 at the end of regulation").isEqualTo(new SportsOutcome.Result.Winner("Under 205.5"));
        assertThat(SportsOutcome.decide(total("205.5", true), overtime, null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Winner("Over 205.5"));
        GameLookup.Game missingPeriods = game(GameStatus.FINAL, "final", 120, 112, List.of(25, 25), List.of(30));
        assertThat(SportsOutcome.decide(total("205.5", false), missingPeriods, null, AFTER_GRACE))
                .isInstanceOf(SportsOutcome.Result.Wait.class);
    }

    @Test
    void waitsForTheGracePeriodAndNeverSettlesWithoutFinal() {
        assertThat(SportsOutcome.decide(total("224.5", true), finished(120, 110), null, AFTER_GRACE.minusSeconds(1)))
                .isInstanceOf(SportsOutcome.Result.Wait.class);
        assertThat(SportsOutcome.decide(total("224.5", true),
                game(GameStatus.LIVE, "in_progress", 120, 110, List.of(), List.of()), null, AFTER_GRACE))
                .isInstanceOf(SportsOutcome.Result.Wait.class);
        assertThat(SportsOutcome.decide(total("224.5", true), null, null, AFTER_GRACE))
                .isInstanceOf(SportsOutcome.Result.Wait.class);
    }

    @Test
    void canceledGamesWashAndSuspendedOnesWaitUpToFortyEightHours() {
        GameLookup.Game canceled = game(GameStatus.POSTPONED, "canceled", 0, 0, List.of(), List.of());
        assertThat(SportsOutcome.decide(total("224.5", true), canceled, null, AFTER_GRACE))
                .isEqualTo(new SportsOutcome.Result.Wash("Game canceled or postponed"));
        GameLookup.Game suspended = game(GameStatus.POSTPONED, "suspended", 50, 48, List.of(), List.of());
        Instant since = Instant.parse("2026-11-14T02:00:00Z");
        assertThat(SportsOutcome.decide(total("224.5", true), suspended, since,
                since.plus(Duration.ofHours(47)))).isInstanceOf(SportsOutcome.Result.Wait.class);
        assertThat(SportsOutcome.decide(total("224.5", true), suspended, since, since.plus(Duration.ofHours(48))))
                .isEqualTo(new SportsOutcome.Result.Wash("Game suspended for more than 48 hours"));
    }

    @Test
    void anEndlessWaitWashesFortyEightHoursAfterTheScheduledStart() {
        GameLookup.Game live = game(GameStatus.LIVE, "in_progress", 50, 48, List.of(), List.of());
        Instant start = live.startsAt();
        SportsOutcome.Result waiting = new SportsOutcome.Result.Wait();
        assertThat(SportsOutcome.orNoResult(waiting, live, null, start.plus(Duration.ofHours(48)).minusSeconds(1)))
                .isEqualTo(waiting);
        assertThat(SportsOutcome.orNoResult(waiting, live, null, start.plus(Duration.ofHours(48))))
                .isEqualTo(new SportsOutcome.Result.Wash(SportsOutcome.NO_RESULT));
        SportsOutcome.Result winner = new SportsOutcome.Result.Winner("Over 224.5");
        assertThat(SportsOutcome.orNoResult(winner, live, null, start.plus(Duration.ofDays(9)))).isEqualTo(winner);
        GameLookup.Game suspended = game(GameStatus.POSTPONED, "suspended", 50, 48, List.of(), List.of());
        assertThat(SportsOutcome.orNoResult(waiting, suspended, null, start.plus(Duration.ofDays(9))))
                .as("suspensions keep their own rule").isEqualTo(waiting);
        Instant locked = Instant.parse("2026-11-10T00:00:00Z");
        assertThat(SportsOutcome.orNoResult(waiting, null, locked, locked.plus(Duration.ofHours(48))))
                .as("no stored game: counts from the lock").isEqualTo(new SportsOutcome.Result.Wash(SportsOutcome.NO_RESULT));
    }

    @Test
    void combinedScoreUsesRegulationOnlyWhenOvertimeIsExcludedAndOver() {
        GameLookup.Game overtime = game(GameStatus.LIVE, "in_progress", 110, 108, List.of(25, 25, 25, 25, 10),
                List.of(25, 25, 25, 25, 8));
        assertThat(SportsOutcome.combinedScore(overtime, true)).isEqualTo(218);
        assertThat(SportsOutcome.combinedScore(overtime, false)).isEqualTo(200);
        GameLookup.Game early = game(GameStatus.LIVE, "in_progress", 30, 20, List.of(30), List.of(20));
        assertThat(SportsOutcome.combinedScore(early, false)).as("regulation not over yet").isEqualTo(50);
    }
}
