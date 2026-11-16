package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.PlayerStatsLookup.ScoreUpdate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scorcerer (NFL): how the first score after picks lock is made, read from the stored score updates (domain rules,
 * "Wash triggers"). One team's score rising by 6–8 is a touchdown (with its extra point or conversion), 3 a field
 * goal, 2 a safety. An extra point or two-point conversion arriving after lock for a touchdown scored before it
 * isn't a new score. Both teams scoring in one update, or a jump that isn't one scoring play, washes. If the game
 * ends with no score after lock, "No more scoring" wins (after the grace period). Pure.
 */
final class ScorcererOutcome {
    static final String UNREADABLE = "Couldn't tell the scoring play";
    /** A +1 or +2 this soon after a touchdown by the same team is that touchdown's try, not a new score. */
    static final Duration TRY_WINDOW = Duration.ofMinutes(5);

    private ScorcererOutcome() {}

    static SportsOutcome.Result decide(SportsOutcome.Terms terms, GameLookup.Game game, Instant suspendedSince,
            Instant now, List<ScoreUpdate> scores) {
        SportsOutcome.Result interrupted = SportsOutcome.interruption(game, suspendedSince, now);
        if (interrupted != null) return interrupted;
        Instant lockedAt = terms.lockedAt();
        if (lockedAt == null) return new SportsOutcome.Result.Wait();
        ScoreUpdate baseline = new ScoreUpdate(lockedAt, null, 0, 0);
        Instant lastTouchdown = null;
        boolean lastTouchdownHome = false;
        for (int i = 0; i < scores.size(); i++) {
            ScoreUpdate update = scores.get(i);
            int dh = update.home() - baseline.home();
            int da = update.away() - baseline.away();
            if (update.observedAt().isAfter(lockedAt) && (dh != 0 || da != 0)) {
                if (dh < 0 || da < 0 || (dh > 0 && da > 0)) return new SportsOutcome.Result.Wash(UNREADABLE);
                boolean home = dh > 0;
                int points = home ? dh : da;
                boolean tryForEarlierTouchdown = (points == 1 || points == 2) && lastTouchdown != null
                        && lastTouchdownHome == home && !update.observedAt().isAfter(lastTouchdown.plus(TRY_WINDOW))
                        && !lastTouchdown.isAfter(lockedAt);
                if (tryForEarlierTouchdown) {
                    baseline = update;
                    lastTouchdown = null;
                    continue;
                }
                return switch (points) {
                    case 6, 7, 8 -> new SportsOutcome.Result.Winner(terms.choices().get(0));
                    case 3 -> new SportsOutcome.Result.Winner(terms.choices().get(1));
                    case 2 -> new SportsOutcome.Result.Winner(terms.choices().get(2));
                    default -> new SportsOutcome.Result.Wash(UNREADABLE);
                };
            }
            // Before lock: follow the score, and remember the latest touchdown for its try.
            if (dh >= 6 || da >= 6) {
                lastTouchdown = update.observedAt();
                lastTouchdownHome = dh >= 6;
            }
            baseline = update;
        }
        return SportsOutcome.settledFinal(game, now) ? new SportsOutcome.Result.Winner(terms.choices().get(3))
                : new SportsOutcome.Result.Wait();
    }
}
