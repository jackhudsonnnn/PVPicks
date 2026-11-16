package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.PlayerStatsLookup.ScoreUpdate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorcererOutcomeTest {
    private static final Instant LOCK = Instant.parse("2026-11-15T19:00:00Z");
    private static final Instant FINAL_AT = LOCK.plusSeconds(3600);
    private static final SportsOutcome.Terms TERMS = new SportsOutcome.Terms("scorcerer", null, true,
            SportsProposalService.SCORCERER_CHOICES, null, List.of(), LOCK);

    private static GameLookup.Game game(GameStatus status) {
        return new GameLookup.Game("NFL-1", League.NFL, status, null, LOCK.minusSeconds(3600),
                status == GameStatus.FINAL ? FINAL_AT : null, new GameLookup.Team("DET", 0, List.of()),
                new GameLookup.Team("GB", 0, List.of()));
    }

    private static ScoreUpdate at(int secondsAfterLock, int home, int away) {
        return new ScoreUpdate(LOCK.plusSeconds(secondsAfterLock), 2, home, away);
    }

    private static SportsOutcome.Result decide(GameStatus status, Instant now, ScoreUpdate... updates) {
        return ScorcererOutcome.decide(TERMS, game(status), null, now, List.of(updates));
    }

    @Test
    void readsTheFirstScoreAfterLockFromTheJump() {
        Instant now = LOCK.plusSeconds(600);
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3), at(120, 14, 3)))
                .isEqualTo(new SportsOutcome.Result.Winner("Touchdown"));
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3), at(120, 7, 6)))
                .isEqualTo(new SportsOutcome.Result.Winner("Field goal"));
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3), at(120, 7, 5)))
                .isEqualTo(new SportsOutcome.Result.Winner("Safety"));
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3), at(120, 10, 10)))
                .as("both teams in one update").isEqualTo(new SportsOutcome.Result.Wash(ScorcererOutcome.UNREADABLE));
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3), at(120, 17, 3)))
                .as("touchdown and field goal in one update").isEqualTo(new SportsOutcome.Result.Wash(ScorcererOutcome.UNREADABLE));
        assertThat(decide(GameStatus.LIVE, now, at(-60, 7, 3))).isEqualTo(new SportsOutcome.Result.Wait());
    }

    @Test
    void theTryForATouchdownBeforeLockIsntTheNextScore() {
        Instant now = LOCK.plusSeconds(900);
        assertThat(decide(GameStatus.LIVE, now, at(-300, 0, 0), at(-30, 6, 0), at(60, 7, 0), at(600, 7, 3)))
                .as("the extra point is skipped").isEqualTo(new SportsOutcome.Result.Winner("Field goal"));
        assertThat(decide(GameStatus.LIVE, now, at(-300, 0, 0), at(-30, 6, 0), at(60, 8, 0), at(600, 8, 7)))
                .as("a two-point try isn't a safety").isEqualTo(new SportsOutcome.Result.Winner("Touchdown"));
    }

    @Test
    void noScoreBeforeTheEndIsNoMoreScoringAfterTheGrace() {
        assertThat(decide(GameStatus.FINAL, FINAL_AT.plus(SportsOutcome.GRACE), at(-60, 21, 17)))
                .isEqualTo(new SportsOutcome.Result.Winner("No more scoring"));
        assertThat(decide(GameStatus.FINAL, FINAL_AT, at(-60, 21, 17))).isEqualTo(new SportsOutcome.Result.Wait());
    }
}
