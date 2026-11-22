package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Automatic results for sports bets from the stored game (domain rules, "Sports outcomes and host overrides" and
 * "Sports bet rules"). Pure: the caller loads the game and applies the result. Never settles on a feed outage or an
 * unknown state; a host can always override.
 */
final class SportsOutcome {
    /** Final settles after this long, so quick stat corrections land first. */
    static final Duration GRACE = Duration.ofMinutes(20);
    /** A suspended game that hasn't finished by then washes. */
    static final Duration SUSPENSION_LIMIT = Duration.ofHours(48);
    /** A bet still without an automatic result this long after its game's scheduled start washes. */
    static final Duration NO_RESULT_LIMIT = Duration.ofHours(48);
    static final String NO_RESULT = "No result from the feed";

    sealed interface Result {
        /** Not decidable yet; check again later. */
        record Wait() implements Result {}

        record Winner(String choice) implements Result {}

        record Wash(String reason) implements Result {}
    }

    /**
     * A sports bet's settled terms. {@code choices} are in the order the proposal built them; {@code stat} and
     * {@code players} are for the player modes; {@code lockedAt} is when picks locked (null while Active).
     */
    record Terms(String mode, BigDecimal line, boolean includesOvertime, List<String> choices, String stat,
                 List<String> players, java.time.Instant lockedAt) {
        Terms(String mode, BigDecimal line, boolean includesOvertime, List<String> choices) {
            this(mode, line, includesOvertime, choices, null, List.of(), null);
        }
    }

    private SportsOutcome() {}

    /**
     * Turns an endless wait into a wash: a game stuck in an unknown or live state, or player stats that never
     * arrive, would otherwise leave the bet Pending until a host steps in (domain rules, "Wash triggers"). Measured
     * from the game's current scheduled start, so a rescheduled game gets its full window; suspended games keep their
     * own 48-hour rule. Without a stored game, it counts from when picks locked.
     */
    static Result orNoResult(Result result, GameLookup.Game game, Instant lockedAt, Instant now) {
        if (!(result instanceof Result.Wait)) return result;
        if (game != null && game.status() == GameStatus.POSTPONED && "suspended".equals(game.providerState())) {
            return result;
        }
        Instant from = game != null ? game.startsAt() : lockedAt;
        return from != null && !now.isBefore(from.plus(NO_RESULT_LIMIT)) ? new Result.Wash(NO_RESULT) : result;
    }

    /**
     * The combined score that counts for a Total Disaster bet right now: the whole game, or with "Overtime counts"
     * off and regulation over, the regulation total. Missing scores (before the game starts) count as 0.
     */
    static int combinedScore(GameLookup.Game game, boolean includesOvertime) {
        Integer home = includesOvertime ? null : game.home().regulationScore();
        Integer away = includesOvertime ? null : game.away().regulationScore();
        if (home == null || away == null) {
            home = game.home().score();
            away = game.away().score();
        }
        return (home == null ? 0 : home) + (away == null ? 0 : away);
    }

    /**
     * What a canceled, postponed or suspended game means for any sports bet: a wash, a wait, or null when the
     * game is going ahead (scheduled, live or final).
     */
    static Result interruption(GameLookup.Game game, Instant suspendedSince, Instant now) {
        if (game == null) return new Result.Wait();
        if (game.status() != GameStatus.POSTPONED) return null;
        if ("suspended".equals(game.providerState())) {
            return suspendedSince != null && !now.isBefore(suspendedSince.plus(SUSPENSION_LIMIT))
                    ? new Result.Wash("Game suspended for more than 48 hours") : new Result.Wait();
        }
        return new Result.Wash("Game canceled or postponed");
    }

    /** Final, and the grace period for stat corrections has passed. */
    static boolean settledFinal(GameLookup.Game game, Instant now) {
        return game.status() == GameStatus.FINAL && game.finalAt() != null && !now.isBefore(game.finalAt().plus(GRACE));
    }

    static Result decide(Terms terms, GameLookup.Game game, Instant suspendedSince, Instant now) {
        Result interrupted = interruption(game, suspendedSince, now);
        if (interrupted != null) return interrupted;
        if (!settledFinal(game, now)) return new Result.Wait();
        Integer home = terms.includesOvertime() ? game.home().score() : game.home().regulationScore();
        Integer away = terms.includesOvertime() ? game.away().score() : game.away().regulationScore();
        if (home == null || away == null) return new Result.Wait();
        return switch (terms.mode()) {
            case "total_disaster" -> {
                int compare = BigDecimal.valueOf(home + away).compareTo(terms.line());
                yield compare > 0 ? new Result.Winner(terms.choices().get(0))
                        : compare < 0 ? new Result.Winner(terms.choices().get(1)) : new Result.Wash("Push");
            }
            case "give_and_take" -> {
                // The line is the home team's spread: home covers when home + spread beats away.
                int compare = BigDecimal.valueOf(home).add(terms.line()).compareTo(BigDecimal.valueOf(away));
                yield compare > 0 ? new Result.Winner(terms.choices().get(0))
                        : compare < 0 ? new Result.Winner(terms.choices().get(1)) : new Result.Wash("Push");
            }
            default -> new Result.Wait();
        };
    }
}
