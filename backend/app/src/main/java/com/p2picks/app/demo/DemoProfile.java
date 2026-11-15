package com.p2picks.app.demo;

import com.p2picks.app.sports.League;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * How a demo user plays. Every parameter has a default ({@link #DEFAULT}); an {@link Archetype} changes only a few,
 * and {@link #jitter} gives each user small variation. Chances are 0–1; rates are expected events per hour or day.
 * See docs/site/demo-data.md for what each parameter drives.
 *
 * @param followBackChance chance of following back someone who follows them
 * @param inviteAcceptChance chance of accepting a table invitation
 * @param followsPerDay new people they follow on their own
 * @param blockChance chance of blocking a table-mate after a bad beat or trash talk
 * @param reportChance chance of reporting a message aimed at them
 * @param joinBetChance chance of picking on a pending bet they can see
 * @param proposalsPerHour bets they propose in one of their tables
 * @param modes mode ids they propose; empty means every available mode
 * @param leagues leagues they bet on
 * @param pickStyle how they choose a side
 * @param wagerMinCents smallest wager they propose
 * @param wagerMaxCents largest wager they propose
 * @param reactionMinSeconds shortest delay before acting
 * @param reactionMaxSeconds longest delay before acting
 * @param messagesPerHour chat messages in an active table
 * @param gifChance chance a message is a GIF
 * @param reactionChance chance of reacting to a message they see
 * @param trashTalkChance chance of a canned line after a win or loss
 * @param settleStyle what they do with receipts
 * @param activeFrom start of their active hours (America/New_York)
 * @param activeUntil end of their active hours; before {@code activeFrom} means past midnight, and the same as
 *     {@code activeFrom} means all day
 * @param onlineChance share of their active hours they're actually around
 */
public record DemoProfile(
        double followBackChance,
        double inviteAcceptChance,
        double followsPerDay,
        double blockChance,
        double reportChance,
        double joinBetChance,
        double proposalsPerHour,
        Set<String> modes,
        Set<League> leagues,
        PickStyle pickStyle,
        long wagerMinCents,
        long wagerMaxCents,
        int reactionMinSeconds,
        int reactionMaxSeconds,
        double messagesPerHour,
        double gifChance,
        double reactionChance,
        double trashTalkChance,
        SettleStyle settleStyle,
        LocalTime activeFrom,
        LocalTime activeUntil,
        double onlineChance) {

    public enum PickStyle { FAVORITE, UNDERDOG, HOME, RANDOM, CONTRARIAN }

    public enum SettleStyle { PROMPT, SLOW, FORGIVES, DISPUTES }

    /** The casual default every archetype starts from. */
    public static final DemoProfile DEFAULT = new DemoProfile(
            0.7, 0.6, 0.1, 0.02, 0.01,
            0.5, 0.3, Set.of(), EnumSet.allOf(League.class), PickStyle.RANDOM, 100, 500, 10, 120,
            0.5, 0.15, 0.2, 0.3,
            SettleStyle.PROMPT, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 0.9);

    public DemoProfile {
        modes = Set.copyOf(modes);
        leagues = leagues.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(leagues));
        for (double chance : new double[] {followBackChance, inviteAcceptChance, blockChance, reportChance,
                joinBetChance, gifChance, reactionChance, trashTalkChance, onlineChance}) {
            if (chance < 0 || chance > 1) throw new IllegalArgumentException("Chances are 0–1, got " + chance);
        }
        if (followsPerDay < 0 || proposalsPerHour < 0 || messagesPerHour < 0) {
            throw new IllegalArgumentException("Rates can't be negative");
        }
        if (wagerMinCents < 10 || wagerMaxCents > 1_000 || wagerMaxCents < wagerMinCents) {
            throw new IllegalArgumentException("Wagers are 10–1,000 cents (the bet rule), min ≤ max");
        }
        if (reactionMinSeconds < 0 || reactionMaxSeconds < reactionMinSeconds) {
            throw new IllegalArgumentException("Reaction delay min ≤ max");
        }
    }

    /** A copy with a few parameters changed, e.g. {@code DEFAULT.with(p -> p.joinBetChance(0.9))}. */
    public DemoProfile with(UnaryOperator<Builder> changes) {
        return changes.apply(new Builder(this)).build();
    }

    /** Small per-user variation: chances and rates move up to ±20% (chances stay within 0–1). */
    DemoProfile jitter(Random random) {
        return with(p -> p
                .followBackChance(chance(random, followBackChance))
                .inviteAcceptChance(chance(random, inviteAcceptChance))
                .followsPerDay(rate(random, followsPerDay))
                .blockChance(chance(random, blockChance))
                .reportChance(chance(random, reportChance))
                .joinBetChance(chance(random, joinBetChance))
                .proposalsPerHour(rate(random, proposalsPerHour))
                .messagesPerHour(rate(random, messagesPerHour))
                .gifChance(chance(random, gifChance))
                .reactionChance(chance(random, reactionChance))
                .trashTalkChance(chance(random, trashTalkChance))
                .onlineChance(chance(random, onlineChance)));
    }

    private static double rate(Random random, double value) {
        return value * (0.8 + 0.4 * random.nextDouble());
    }

    private static double chance(Random random, double value) {
        return Math.min(1, rate(random, value));
    }

    /** Starting points for demo users; each changes only what differs from {@link #DEFAULT}. */
    public enum Archetype {
        CASUAL(35, p -> p),
        DEGEN(15, p -> p.joinBetChance(0.9).proposalsPerHour(1.5).wager(500, 1_000).messagesPerHour(1.5)),
        LURKER(20, p -> p.joinBetChance(0.2).messagesPerHour(0.05)),
        SHARP(10, p -> p.pickStyle(PickStyle.FAVORITE).settleStyle(SettleStyle.PROMPT).trashTalkChance(0.05)),
        TROLL(5, p -> p.trashTalkChance(0.8).blockChance(0.1).reportChance(0.1).settleStyle(SettleStyle.DISPUTES)),
        FRIENDLY(15, p -> p.followBackChance(0.95).inviteAcceptChance(0.9).settleStyle(SettleStyle.FORGIVES));

        /** Relative share of demo users with this archetype. */
        final int weight;
        private final UnaryOperator<Builder> changes;

        Archetype(int weight, UnaryOperator<Builder> changes) {
            this.weight = weight;
            this.changes = changes;
        }

        public DemoProfile profile() {
            return DEFAULT.with(changes);
        }
    }

    public static final class Builder {
        private double followBackChance;
        private double inviteAcceptChance;
        private double followsPerDay;
        private double blockChance;
        private double reportChance;
        private double joinBetChance;
        private double proposalsPerHour;
        private Set<String> modes;
        private Set<League> leagues;
        private PickStyle pickStyle;
        private long wagerMinCents;
        private long wagerMaxCents;
        private int reactionMinSeconds;
        private int reactionMaxSeconds;
        private double messagesPerHour;
        private double gifChance;
        private double reactionChance;
        private double trashTalkChance;
        private SettleStyle settleStyle;
        private LocalTime activeFrom;
        private LocalTime activeUntil;
        private double onlineChance;

        private Builder(DemoProfile p) {
            followBackChance = p.followBackChance;
            inviteAcceptChance = p.inviteAcceptChance;
            followsPerDay = p.followsPerDay;
            blockChance = p.blockChance;
            reportChance = p.reportChance;
            joinBetChance = p.joinBetChance;
            proposalsPerHour = p.proposalsPerHour;
            modes = p.modes;
            leagues = p.leagues;
            pickStyle = p.pickStyle;
            wagerMinCents = p.wagerMinCents;
            wagerMaxCents = p.wagerMaxCents;
            reactionMinSeconds = p.reactionMinSeconds;
            reactionMaxSeconds = p.reactionMaxSeconds;
            messagesPerHour = p.messagesPerHour;
            gifChance = p.gifChance;
            reactionChance = p.reactionChance;
            trashTalkChance = p.trashTalkChance;
            settleStyle = p.settleStyle;
            activeFrom = p.activeFrom;
            activeUntil = p.activeUntil;
            onlineChance = p.onlineChance;
        }

        public Builder followBackChance(double value) { followBackChance = value; return this; }
        public Builder inviteAcceptChance(double value) { inviteAcceptChance = value; return this; }
        public Builder followsPerDay(double value) { followsPerDay = value; return this; }
        public Builder blockChance(double value) { blockChance = value; return this; }
        public Builder reportChance(double value) { reportChance = value; return this; }
        public Builder joinBetChance(double value) { joinBetChance = value; return this; }
        public Builder proposalsPerHour(double value) { proposalsPerHour = value; return this; }
        public Builder modes(Set<String> value) { modes = value; return this; }
        public Builder leagues(Set<League> value) { leagues = value; return this; }
        public Builder pickStyle(PickStyle value) { pickStyle = value; return this; }
        public Builder wager(long minCents, long maxCents) { wagerMinCents = minCents; wagerMaxCents = maxCents; return this; }
        public Builder reactionSeconds(int min, int max) { reactionMinSeconds = min; reactionMaxSeconds = max; return this; }
        public Builder messagesPerHour(double value) { messagesPerHour = value; return this; }
        public Builder gifChance(double value) { gifChance = value; return this; }
        public Builder reactionChance(double value) { reactionChance = value; return this; }
        public Builder trashTalkChance(double value) { trashTalkChance = value; return this; }
        public Builder settleStyle(SettleStyle value) { settleStyle = value; return this; }
        public Builder activeHours(LocalTime from, LocalTime until) { activeFrom = from; activeUntil = until; return this; }
        public Builder onlineChance(double value) { onlineChance = value; return this; }

        public DemoProfile build() {
            return new DemoProfile(followBackChance, inviteAcceptChance, followsPerDay, blockChance, reportChance,
                    joinBetChance, proposalsPerHour, modes, leagues, pickStyle, wagerMinCents, wagerMaxCents,
                    reactionMinSeconds, reactionMaxSeconds, messagesPerHour, gifChance, reactionChance,
                    trashTalkChance, settleStyle, activeFrom, activeUntil, onlineChance);
        }
    }
}
