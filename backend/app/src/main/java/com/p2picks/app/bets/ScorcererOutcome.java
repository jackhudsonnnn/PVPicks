package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.PlayerStatsLookup.ScoreUpdate;
import com.p2picks.app.sports.PlayerStatsLookup.ScoringTotals;
import com.p2picks.app.sports.PlayerStatsLookup.TeamScoring;
import java.time.Instant;
import java.util.List;

/**
 * Scorcerer: which team scores next after picks lock, and how (domain rules, "Scorcerer"; player stats, "Scorcerer
 * from team totals"). Five choices, away team first: away big, away small, home big, home small, no more scores.
 * NFL: touchdown or field goal (safeties and every other 2-point play don't count). NBA: a 3-pointer or a 2-pointer
 * (free throws don't count).
 *
 * <p>What scored comes from team totals of player stats; the score only checks them. The totals "explain" a team's
 * score when they add up to it: exactly in the NBA (free throws included), and in the NFL with up to two 2-point
 * plays the feed doesn't report. Decisions wait until the latest totals explain the latest score, so neither feed is
 * ahead of the other. Pure.
 *
 * <ol>
 *   <li>Baseline: the totals at lock, which must explain the score at lock. If the stats were behind, later totals
 *       may catch up, as long as the score hasn't moved since lock; otherwise the bet washes.</li>
 *   <li>The first observation after the baseline where any of the four counts rises decides it. Two different ones
 *       rising in one observation washes, because their order is unknown; the same one rising twice still decides
 *       it.</li>
 *   <li>None by Final plus the grace period means "No more scores". Totals that still don't explain the score then
 *       wash.</li>
 * </ol>
 */
final class ScorcererOutcome {
    static final String UNREADABLE = "Couldn't tell the scoring play";
    /** NFL 2-point plays per team (safeties, two-point conversions, defensive returns) the totals may leave out. */
    static final int MAX_TWO_POINT_PLAYS = 2;

    private ScorcererOutcome() {}

    /** The five choices, in order: away big, away small, home big, home small, none. */
    static List<String> choices(League league, String away, String home) {
        String big = league == League.NFL ? "touchdown" : "3";
        String small = league == League.NFL ? "field goal" : "2";
        return List.of(away + " " + big, away + " " + small, home + " " + big, home + " " + small, "No more scores");
    }

    static SportsOutcome.Result decide(SportsOutcome.Terms terms, GameLookup.Game game, Instant suspendedSince,
            Instant now, List<ScoreUpdate> scores, List<ScoringTotals> totals) {
        SportsOutcome.Result interrupted = SportsOutcome.interruption(game, suspendedSince, now);
        if (interrupted != null) return interrupted;
        Instant lockedAt = terms.lockedAt();
        if (lockedAt == null) return new SportsOutcome.Result.Wait();
        League league = game.league();
        boolean settled = SportsOutcome.settledFinal(game, now);
        SportsOutcome.Result notYet = settled ? new SportsOutcome.Result.Wash(UNREADABLE)
                : new SportsOutcome.Result.Wait();
        ScoreUpdate atLock = scoreAt(scores, lockedAt);

        int next = 0;
        ScoringTotals base = new ScoringTotals(lockedAt, TeamScoring.NONE, TeamScoring.NONE);
        while (next < totals.size() && !totals.get(next).observedAt().isAfter(lockedAt)) base = totals.get(next++);
        if (!explains(league, base, atLock.home(), atLock.away())) {
            // The stats were behind the score at lock: let them catch up while the score stands still.
            boolean caughtUp = false;
            while (next < totals.size()) {
                ScoringTotals later = totals.get(next++);
                ScoreUpdate score = scoreAt(scores, later.observedAt());
                if (score.home() != atLock.home() || score.away() != atLock.away()) {
                    return new SportsOutcome.Result.Wash(UNREADABLE);
                }
                if (explains(league, later, atLock.home(), atLock.away())) {
                    base = later;
                    caughtUp = true;
                    break;
                }
            }
            if (!caughtUp) return notYet;
        }

        ScoringTotals latest = totals.isEmpty() ? base : totals.getLast();
        boolean current = explains(league, latest, score(game.home()), score(game.away()));
        for (; next < totals.size(); next++) {
            ScoringTotals observed = totals.get(next);
            // In choice order: away big, away small, home big, home small.
            boolean[] rose = {
                    observed.away().big() > base.away().big(), observed.away().small() > base.away().small(),
                    observed.home().big() > base.home().big(), observed.home().small() > base.home().small()};
            int kinds = 0;
            int which = -1;
            for (int i = 0; i < rose.length; i++) {
                if (rose[i]) {
                    kinds++;
                    which = i;
                }
            }
            if (kinds > 1) return new SportsOutcome.Result.Wash(UNREADABLE);
            if (kinds == 1) {
                if (!current) return notYet;
                return new SportsOutcome.Result.Winner(terms.choices().get(which));
            }
        }
        if (!settled) return new SportsOutcome.Result.Wait();
        return current ? new SportsOutcome.Result.Winner(terms.choices().get(4))
                : new SportsOutcome.Result.Wash(UNREADABLE);
    }

    /** Whether the totals explain both scores (NFL: leaving only a few 2-point plays unaccounted for). */
    static boolean explains(League league, ScoringTotals totals, int home, int away) {
        return explains(league, totals.home(), home) && explains(league, totals.away(), away);
    }

    private static boolean explains(League league, TeamScoring team, int score) {
        int unexplained = score - team.points(league);
        if (league == League.NBA) return unexplained == 0;
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
