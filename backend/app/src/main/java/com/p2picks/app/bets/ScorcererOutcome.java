package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.PlayerStatsLookup.ScoreUpdate;
import com.p2picks.app.sports.PlayerStatsLookup.ScoringTotals;
import com.p2picks.app.sports.PlayerStatsLookup.TeamScoring;
import java.time.Instant;
import java.util.List;

/**
 * Scorcerer (NFL): is the next touchdown or field goal after picks lock a touchdown or a field goal, or does neither
 * come before the end (domain rules, "Wash triggers"; player stats, "Scorcerer from team totals"). Safeties and every
 * other 2-point play don't count.
 *
 * <p>What scored comes from team totals of player stats (touchdowns, field goals, extra points); the score only checks
 * them. The totals "explain" a team's score when the score minus 6 per touchdown, 3 per field goal and 1 per extra
 * point is an even number from 0 to 4: the 2-point plays the feed doesn't report. Decisions wait until the latest
 * totals explain the latest score, so neither feed is ahead of the other. Pure.
 *
 * <ol>
 *   <li>Baseline: the totals at lock, which must explain the score at lock. If the stats were behind, later totals
 *       may catch up, as long as the score hasn't moved since lock; otherwise the bet washes.</li>
 *   <li>The first observation after the baseline where touchdowns or field goals rise decides it. Both rising in one
 *       observation washes, because their order is unknown; two touchdowns, or two field goals, still decide it.</li>
 *   <li>Neither by Final plus the grace period means "No more TDs or FGs". Totals that still don't explain the score
 *       then wash.</li>
 * </ol>
 */
final class ScorcererOutcome {
    static final String UNREADABLE = "Couldn't tell the scoring play";
    /** 2-point plays per team (safeties, two-point conversions, defensive returns) the totals may leave unexplained. */
    static final int MAX_TWO_POINT_PLAYS = 2;

    private ScorcererOutcome() {}

    static SportsOutcome.Result decide(SportsOutcome.Terms terms, GameLookup.Game game, Instant suspendedSince,
            Instant now, List<ScoreUpdate> scores, List<ScoringTotals> totals) {
        SportsOutcome.Result interrupted = SportsOutcome.interruption(game, suspendedSince, now);
        if (interrupted != null) return interrupted;
        Instant lockedAt = terms.lockedAt();
        if (lockedAt == null) return new SportsOutcome.Result.Wait();
        boolean settled = SportsOutcome.settledFinal(game, now);
        SportsOutcome.Result notYet = settled ? new SportsOutcome.Result.Wash(UNREADABLE)
                : new SportsOutcome.Result.Wait();
        ScoreUpdate atLock = scoreAt(scores, lockedAt);

        int next = 0;
        ScoringTotals base = new ScoringTotals(lockedAt, TeamScoring.NONE, TeamScoring.NONE);
        while (next < totals.size() && !totals.get(next).observedAt().isAfter(lockedAt)) base = totals.get(next++);
        if (!explains(base, atLock.home(), atLock.away())) {
            // The stats were behind the score at lock: let them catch up while the score stands still.
            boolean caughtUp = false;
            while (next < totals.size()) {
                ScoringTotals later = totals.get(next++);
                ScoreUpdate score = scoreAt(scores, later.observedAt());
                if (score.home() != atLock.home() || score.away() != atLock.away()) {
                    return new SportsOutcome.Result.Wash(UNREADABLE);
                }
                if (explains(later, atLock.home(), atLock.away())) {
                    base = later;
                    caughtUp = true;
                    break;
                }
            }
            if (!caughtUp) return notYet;
        }

        ScoringTotals latest = totals.isEmpty() ? base : totals.getLast();
        boolean current = explains(latest, score(game.home()), score(game.away()));
        for (; next < totals.size(); next++) {
            ScoringTotals totalsNow = totals.get(next);
            int touchdowns = totalsNow.home().touchdowns() + totalsNow.away().touchdowns()
                    - base.home().touchdowns() - base.away().touchdowns();
            int fieldGoals = totalsNow.home().fieldGoals() + totalsNow.away().fieldGoals()
                    - base.home().fieldGoals() - base.away().fieldGoals();
            if (touchdowns > 0 && fieldGoals > 0) return new SportsOutcome.Result.Wash(UNREADABLE);
            if (touchdowns > 0 || fieldGoals > 0) {
                if (!current) return notYet;
                return new SportsOutcome.Result.Winner(terms.choices().get(touchdowns > 0 ? 0 : 1));
            }
        }
        if (!settled) return new SportsOutcome.Result.Wait();
        return current ? new SportsOutcome.Result.Winner(terms.choices().get(2))
                : new SportsOutcome.Result.Wash(UNREADABLE);
    }

    /** Whether the totals explain both scores, leaving only a few 2-point plays unaccounted for. */
    static boolean explains(ScoringTotals totals, int home, int away) {
        return explains(totals.home(), home) && explains(totals.away(), away);
    }

    private static boolean explains(TeamScoring team, int score) {
        int unexplained = score - team.points();
        return unexplained >= 0 && unexplained % 2 == 0 && unexplained <= 2 * MAX_TWO_POINT_PLAYS;
    }

    /** The score as last observed at or before {@code at}; 0–0 before the first update. */
    private static ScoreUpdate scoreAt(List<ScoreUpdate> scores, Instant at) {
        ScoreUpdate found = new ScoreUpdate(at, null, 0, 0);
        for (ScoreUpdate update : scores) {
            if (update.observedAt().isAfter(at)) break;
            found = update;
        }
        return found;
    }

    private static int score(GameLookup.Team team) {
        return team.score() == null ? 0 : team.score();
    }
}
