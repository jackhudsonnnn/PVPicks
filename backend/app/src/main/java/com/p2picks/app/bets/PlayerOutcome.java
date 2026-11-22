package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Automatic results for the player modes (domain rules, "Sports bet rules" and "Wash triggers"). Pure: the caller
 * supplies the stored facts. Either Or and Prop Hunt settle after Final plus the grace period; King Of The Hill
 * settles as soon as someone reaches the target (or after Final, for Neither). With "Overtime counts" off, values
 * are the last observed before overtime began, and a target reached in overtime doesn't count.
 */
final class PlayerOutcome {
    static final String DID_NOT_PLAY = "A player in the bet didn't play";
    static final String TIE = "Tie";
    static final String SAME_UPDATE = "Both reached it in the same update";

    /** What the stored stats say about the bet's players. */
    interface Facts {
        /** Whether the player appeared in the game (NBA: any minutes; NFL: a stat row). */
        boolean played(String playerId);

        /** The player's current value of the stat. */
        int value(String playerId, String stat);

        /** When overtime began, or null if the game never went to overtime. */
        Instant overtimeStart();

        /** The value last observed before {@code cutoff}. */
        int valueBefore(String playerId, String stat, Instant cutoff);

        /** When the player first reached {@code target}, or null. */
        Instant firstReached(String playerId, String stat, int target);
    }

    private PlayerOutcome() {}

    static SportsOutcome.Result decide(SportsOutcome.Terms terms, GameLookup.Game game, Instant suspendedSince,
            Instant now, Facts facts) {
        SportsOutcome.Result interrupted = SportsOutcome.interruption(game, suspendedSince, now);
        if (interrupted != null) return interrupted;
        boolean settled = SportsOutcome.settledFinal(game, now);
        Instant cutoff = terms.includesOvertime() ? null : facts.overtimeStart();
        List<String> players = terms.players();
        String stat = terms.stat();
        return switch (terms.mode()) {
            case "either_or" -> {
                if (!settled) yield new SportsOutcome.Result.Wait();
                if (players.stream().anyMatch(player -> !facts.played(player))) {
                    yield new SportsOutcome.Result.Wash(DID_NOT_PLAY);
                }
                int first = value(facts, players.get(0), stat, cutoff);
                int second = value(facts, players.get(1), stat, cutoff);
                yield first == second ? new SportsOutcome.Result.Wash(TIE)
                        : new SportsOutcome.Result.Winner(terms.choices().get(first > second ? 0 : 1));
            }
            case "prop_hunt" -> {
                if (!settled) yield new SportsOutcome.Result.Wait();
                String player = players.getFirst();
                if (!facts.played(player)) yield new SportsOutcome.Result.Wash(DID_NOT_PLAY);
                int compare = BigDecimal.valueOf(value(facts, player, stat, cutoff)).compareTo(terms.line());
                yield compare > 0 ? new SportsOutcome.Result.Winner(terms.choices().get(0))
                        : compare < 0 ? new SportsOutcome.Result.Winner(terms.choices().get(1))
                        : new SportsOutcome.Result.Wash("Push");
            }
            case "king_of_the_hill" -> {
                int target = terms.line().intValueExact();
                Instant first = reached(facts, players.get(0), stat, target, cutoff);
                Instant second = reached(facts, players.get(1), stat, target, cutoff);
                if (first != null || second != null) {
                    if (first != null && first.equals(second)) yield new SportsOutcome.Result.Wash(SAME_UPDATE);
                    boolean firstWins = second == null || (first != null && first.isBefore(second));
                    yield new SportsOutcome.Result.Winner(terms.choices().get(firstWins ? 0 : 1));
                }
                if (!settled) yield new SportsOutcome.Result.Wait();
                if (players.stream().anyMatch(player -> !facts.played(player))) {
                    yield new SportsOutcome.Result.Wash(DID_NOT_PLAY);
                }
                yield new SportsOutcome.Result.Winner(terms.choices().get(2));
            }
            default -> new SportsOutcome.Result.Wait();
        };
    }

    private static int value(Facts facts, String player, String stat, Instant cutoff) {
        return cutoff == null ? facts.value(player, stat) : facts.valueBefore(player, stat, cutoff);
    }

    /** When the player reached the target, ignoring a time at or after the overtime cutoff. */
    private static Instant reached(Facts facts, String player, String stat, int target, Instant cutoff) {
        Instant at = facts.firstReached(player, stat, target);
        return at == null || (cutoff != null && !at.isBefore(cutoff)) ? null : at;
    }
}
