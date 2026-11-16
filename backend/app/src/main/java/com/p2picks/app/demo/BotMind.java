package com.p2picks.app.demo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * A demo user's decisions, as pure functions of their {@link DemoProfile}, the situation and a random source: whether
 * they're around, how long they take, and which side they pick. The bot loop ({@link DemoBots}) does the acting.
 */
final class BotMind {
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** Online status holds for a stretch, so a bot isn't flickering in and out every tick. */
    static final Duration ONLINE_SLOT = Duration.ofMinutes(15);

    private BotMind() {}

    /** Within their active hours, and (stable per 15-minute slot) one of the times they're around. */
    static boolean online(UUID bot, DemoProfile profile, Instant now) {
        LocalTime local = LocalTime.ofInstant(now, NEW_YORK);
        LocalTime from = profile.activeFrom();
        LocalTime until = profile.activeUntil();
        boolean inHours = from.equals(until) || (from.isBefore(until)
                ? !local.isBefore(from) && local.isBefore(until)
                : !local.isBefore(from) || local.isBefore(until));
        if (!inHours) return false;
        long slot = now.getEpochSecond() / ONLINE_SLOT.toSeconds();
        return new Random(bot.getMostSignificantBits() ^ bot.getLeastSignificantBits() ^ slot * 0x9E3779B97F4A7C15L)
                .nextDouble() < profile.onlineChance();
    }

    /** Whether an event with this hourly rate happens within one tick. */
    static boolean happens(Random random, double perHour, Duration tick) {
        return random.nextDouble() < perHour * tick.toMillis() / 3_600_000.0;
    }

    /** How long they take to act, but never past {@code latest} (e.g. a bet's lock, less a margin). */
    static Duration delay(Random random, DemoProfile profile, Duration latest) {
        int span = profile.reactionMaxSeconds() - profile.reactionMinSeconds();
        Duration wanted = Duration.ofSeconds(profile.reactionMinSeconds() + (span == 0 ? 0 : random.nextInt(span + 1)));
        return latest != null && wanted.compareTo(latest) > 0 ? latest : wanted;
    }

    /**
     * Which choice they pick. Score modes list "over" or the home side first ({@code SportsProposalService.terms}).
     *
     * @param mode the bet's mode id
     * @param line the score line ({@code null} for U2Pick)
     * @param counts picks so far per choice (for contrarians and favorites in U2Pick)
     */
    static String pick(Random random, DemoProfile.PickStyle style, String mode, BigDecimal line, List<String> choices,
            Map<String, Integer> counts) {
        boolean spread = mode.equals("give_and_take");
        // Over/under modes list "over" first; it plays as the favorite.
        boolean total = mode.equals("total_disaster") || mode.equals("prop_hunt");
        // Spread: the home side is the favorite when its line is negative. Total: "over" plays as the favorite.
        String favorite = spread ? choices.get(line != null && line.signum() < 0 ? 0 : 1)
                : total ? choices.get(0) : mostPicked(random, choices, counts);
        String underdog = spread ? choices.get(line != null && line.signum() < 0 ? 1 : 0)
                : total ? choices.get(1) : leastPicked(random, choices, counts);
        return switch (style) {
            case FAVORITE -> favorite;
            case UNDERDOG -> underdog;
            case HOME -> spread || !total ? choices.get(0) : choices.get(random.nextInt(choices.size()));
            case CONTRARIAN -> leastPicked(random, choices, counts);
            case RANDOM -> choices.get(random.nextInt(choices.size()));
        };
    }

    private static String mostPicked(Random random, List<String> choices, Map<String, Integer> counts) {
        return ranked(random, choices, counts).getLast();
    }

    private static String leastPicked(Random random, List<String> choices, Map<String, Integer> counts) {
        return ranked(random, choices, counts).getFirst();
    }

    /** Fewest picks first; ties in random order. */
    private static List<String> ranked(Random random, List<String> choices, Map<String, Integer> counts) {
        List<String> shuffled = new ArrayList<>(choices);
        java.util.Collections.shuffle(shuffled, random);
        shuffled.sort(Comparator.comparingInt(choice -> counts.getOrDefault(choice, 0)));
        return shuffled;
    }

    /** A wager in their range, in whole quarter-points. */
    static long wager(Random random, DemoProfile profile) {
        long span = profile.wagerMaxCents() - profile.wagerMinCents();
        long cents = profile.wagerMinCents() + (span == 0 ? 0 : (long) (random.nextDouble() * span));
        return Math.max(10, Math.min(1_000, Math.round(cents / 25.0) * 25));
    }
}
