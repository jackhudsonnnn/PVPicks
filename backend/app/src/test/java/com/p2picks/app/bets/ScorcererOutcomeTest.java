package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.PlayerStatsLookup.ScoreUpdate;
import com.p2picks.app.sports.PlayerStatsLookup.ScoringTotals;
import com.p2picks.app.sports.PlayerStatsLookup.TeamScoring;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorcererOutcomeTest {
    private static final Instant LOCK = Instant.parse("2026-11-15T19:00:00Z");
    private static final Instant FINAL_AT = LOCK.plusSeconds(3600);
    // DET hosts GB.
    private static final SportsOutcome.Terms TERMS = new SportsOutcome.Terms("scorcerer", null, true,
            ScorcererOutcome.choices(League.NFL, "GB", "DET"), null, List.of(), LOCK);
    private static final SportsOutcome.Result WASH = new SportsOutcome.Result.Wash(ScorcererOutcome.UNREADABLE);
    private static final SportsOutcome.Result TD = new SportsOutcome.Result.Winner("DET touchdown");
    private static final SportsOutcome.Result FG = new SportsOutcome.Result.Winner("DET field goal");
    private static final SportsOutcome.Result GB_TD = new SportsOutcome.Result.Winner("GB touchdown");
    private static final SportsOutcome.Result GB_FG = new SportsOutcome.Result.Winner("GB field goal");
    private static final SportsOutcome.Result NEITHER = new SportsOutcome.Result.Winner("No more scores");
    private static final SportsOutcome.Result WAIT = new SportsOutcome.Result.Wait();

    /** A game whose current score is the last score update's. */
    private static GameLookup.Game game(GameStatus status, List<ScoreUpdate> scores) {
        ScoreUpdate last = scores.isEmpty() ? new ScoreUpdate(LOCK, null, 0, 0) : scores.getLast();
        return new GameLookup.Game("NFL-1", League.NFL, status, null, LOCK.minusSeconds(3600),
                status == GameStatus.FINAL ? FINAL_AT : null, new GameLookup.Team("DET", last.home(), List.of()),
                new GameLookup.Team("GB", last.away(), List.of()));
    }

    /** Builds score updates and stats observations in time order, seconds relative to lock. */
    private static final class Feed {
        final List<ScoreUpdate> scores = new ArrayList<>();
        final List<ScoringTotals> totals = new ArrayList<>();

        Feed score(int seconds, int home, int away) {
            scores.add(new ScoreUpdate(LOCK.plusSeconds(seconds), 2, home, away));
            return this;
        }

        /** Touchdowns, field goals and extra points for DET, then GB. */
        Feed stats(int seconds, int homeTd, int homeFg, int homeXp, int awayTd, int awayFg, int awayXp) {
            totals.add(new ScoringTotals(LOCK.plusSeconds(seconds), new TeamScoring(homeTd, homeFg, homeXp),
                    new TeamScoring(awayTd, awayFg, awayXp)));
            return this;
        }

        SportsOutcome.Result decide(GameStatus status, Instant now) {
            return ScorcererOutcome.decide(TERMS, game(status, scores), null, now, scores, totals);
        }

        SportsOutcome.Result live() {
            return decide(GameStatus.LIVE, LOCK.plusSeconds(1800));
        }
    }

    /** DET 7 – GB 3 at lock, with stats that explain it. */
    private static Feed atLock() {
        return new Feed().score(-120, 7, 3).stats(-110, 1, 0, 1, 0, 1, 0);
    }

    @Test
    void theFirstTouchdownOrFieldGoalAfterLockDecides() {
        assertThat(atLock().score(120, 14, 3).stats(130, 2, 0, 2, 0, 1, 0).live()).isEqualTo(TD);
        assertThat(atLock().score(120, 7, 6).stats(130, 1, 0, 1, 0, 2, 0).live()).isEqualTo(GB_FG);
        assertThat(atLock().live()).as("nothing yet").isEqualTo(WAIT);
    }

    @Test
    void safetiesAndTwoPointPlaysAreSkipped() {
        // A safety for GB (no stat changes), then a DET field goal.
        assertThat(atLock().score(120, 7, 5).score(400, 10, 5).stats(410, 1, 1, 1, 0, 1, 0).live()).isEqualTo(FG);
        // A DET touchdown before lock with its two-point try after lock, then a GB touchdown.
        Feed late = new Feed().score(-120, 6, 0).stats(-110, 1, 0, 0, 0, 0, 0).score(60, 8, 0)
                .score(600, 8, 7).stats(610, 1, 0, 0, 1, 0, 1);
        assertThat(late.live()).isEqualTo(GB_TD);
        // A defensive two-point return after a DET touchdown isn't the next score either.
        Feed returned = new Feed().score(-120, 6, 0).stats(-110, 1, 0, 0, 0, 0, 0).score(60, 6, 2)
                .score(600, 9, 2).stats(610, 1, 1, 0, 0, 0, 0);
        assertThat(returned.live()).isEqualTo(FG);
    }

    @Test
    void stalledStatsAtLockCatchUpWhileTheScoreStandsStill() {
        // DET's touchdown is in the score before lock but reaches the stats only after it.
        Feed lagging = new Feed().score(-30, 7, 3).stats(-200, 0, 0, 0, 0, 1, 0).stats(40, 1, 0, 1, 0, 1, 0)
                .score(600, 7, 6).stats(610, 1, 0, 1, 0, 2, 0);
        assertThat(lagging.live()).as("the late touchdown is the baseline, not the next score").isEqualTo(GB_FG);
        // If the score moves again before the stats catch up, the two can't be told apart.
        Feed tangled = new Feed().score(-30, 7, 3).stats(-200, 0, 0, 0, 0, 1, 0).score(20, 7, 6)
                .stats(40, 1, 0, 1, 0, 2, 0);
        assertThat(tangled.live()).isEqualTo(WASH);
    }

    @Test
    void aTouchdownAndAFieldGoalInOneObservationWash() {
        assertThat(atLock().score(120, 17, 3).stats(130, 2, 1, 2, 0, 1, 0).live()).isEqualTo(WASH);
        assertThat(atLock().score(120, 14, 10).stats(130, 2, 0, 2, 1, 1, 1).live())
                .as("a touchdown for each team: order unknown").isEqualTo(WASH);
        assertThat(atLock().score(120, 21, 3).stats(130, 3, 0, 3, 0, 1, 0).live())
                .as("two touchdowns for one team still decide it").isEqualTo(TD);
    }

    @Test
    void waitsWhileTheStatsTrailTheScoreAndWashesIfTheyNeverAgree() {
        // The stats show the touchdown but the score hasn't caught up yet: wait.
        assertThat(atLock().stats(130, 2, 0, 2, 0, 1, 0).live()).isEqualTo(WAIT);
        // A stale touchdown row the score never shows: wash once Final's grace has passed.
        Feed stale = atLock().stats(130, 2, 0, 2, 0, 1, 0);
        assertThat(stale.decide(GameStatus.FINAL, FINAL_AT.plus(SportsOutcome.GRACE))).isEqualTo(WASH);
    }

    @Test
    void neitherBeforeTheEndIsNoMoreTouchdownsOrFieldGoalsAfterTheGrace() {
        assertThat(atLock().decide(GameStatus.FINAL, FINAL_AT.plus(SportsOutcome.GRACE))).isEqualTo(NEITHER);
        assertThat(atLock().decide(GameStatus.FINAL, FINAL_AT)).isEqualTo(WAIT);
        assertThat(atLock().score(900, 7, 5).decide(GameStatus.FINAL, FINAL_AT.plus(SportsOutcome.GRACE)))
                .as("a late safety doesn't count").isEqualTo(NEITHER);
    }

    @Test
    void aStatlessPregameLockStartsFromZero() {
        assertThat(new Feed().score(300, 0, 3).stats(310, 0, 0, 0, 0, 1, 0).live()).isEqualTo(GB_FG);
    }

    // ----- NBA: 3s and 2s, free throws ignored, totals must match the score exactly. BOS hosts NYK.

    private static final SportsOutcome.Terms NBA = new SportsOutcome.Terms("scorcerer", null, true,
            ScorcererOutcome.choices(League.NBA, "NYK", "BOS"), null, List.of(), LOCK);

    private static SportsOutcome.Result nba(Feed feed, GameStatus status, Instant now) {
        ScoreUpdate last = feed.scores.isEmpty() ? new ScoreUpdate(LOCK, null, 0, 0) : feed.scores.getLast();
        GameLookup.Game game = new GameLookup.Game("NBA-1", League.NBA, status, null, LOCK.minusSeconds(3600),
                status == GameStatus.FINAL ? FINAL_AT : null, new GameLookup.Team("BOS", last.home(), List.of()),
                new GameLookup.Team("NYK", last.away(), List.of()));
        return ScorcererOutcome.decide(NBA, game, null, now, feed.scores, feed.totals);
    }

    /** BOS 10 – NYK 8 at lock: BOS two 3s and two 2s; NYK one 3, two 2s and a free throw. */
    private static Feed nbaAtLock() {
        return new Feed().score(-60, 10, 8).stats(-50, 2, 2, 0, 1, 2, 1);
    }

    @Test
    void nbaChoicesNameTheTeamAwayFirst() {
        assertThat(ScorcererOutcome.choices(League.NBA, "NYK", "BOS"))
                .containsExactly("NYK 3", "NYK 2", "BOS 3", "BOS 2", "No more scores");
        assertThat(ScorcererOutcome.choices(League.NFL, "GB", "DET")).containsExactly("GB touchdown",
                "GB field goal", "DET touchdown", "DET field goal", "No more scores");
    }

    @Test
    void nbaTheFirstBasketAfterLockDecidesAndFreeThrowsDont() {
        Instant live = LOCK.plusSeconds(600);
        // Two NYK free throws, then a BOS 3.
        Feed freeThrowsFirst = nbaAtLock().score(30, 10, 10).stats(40, 2, 2, 0, 1, 2, 3)
                .score(90, 13, 10).stats(100, 3, 2, 0, 1, 2, 3);
        assertThat(nba(freeThrowsFirst, GameStatus.LIVE, live)).isEqualTo(new SportsOutcome.Result.Winner("BOS 3"));
        Feed nykTwo = nbaAtLock().score(30, 10, 10).stats(40, 2, 2, 0, 1, 3, 1);
        assertThat(nba(nykTwo, GameStatus.LIVE, live)).isEqualTo(new SportsOutcome.Result.Winner("NYK 2"));
    }

    @Test
    void nbaTwoBasketsOfDifferentKindsInOneUpdateWash() {
        Feed tangled = nbaAtLock().score(30, 12, 11).stats(40, 2, 3, 0, 2, 2, 1);
        assertThat(nba(tangled, GameStatus.LIVE, LOCK.plusSeconds(600))).isEqualTo(WASH);
    }

    @Test
    void nbaTotalsMustMatchTheScoreExactly() {
        // The stats show a BOS 2 but the score hasn't moved yet: wait.
        Feed ahead = nbaAtLock().stats(40, 2, 3, 0, 1, 2, 1);
        assertThat(nba(ahead, GameStatus.LIVE, LOCK.plusSeconds(600))).isEqualTo(WAIT);
        assertThat(nba(nbaAtLock(), GameStatus.FINAL, FINAL_AT.plus(SportsOutcome.GRACE))).isEqualTo(NEITHER);
    }
}
