package com.p2picks.app.bets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ranks Texas hold'em hands: the best five of up to seven cards ("AS", "TD"…; ranks A 2–9 T J Q K, suits S H D C).
 * A hand's {@link Hand#score} orders it against any other (higher wins, equal ties) and {@link Hand#name} says what
 * it is ("Flush, ace high", "Two pair, kings and sevens").
 */
final class HandEvaluator {
    private HandEvaluator() {}

    /** Hand categories, weakest first. */
    enum Category {
        HIGH_CARD, PAIR, TWO_PAIR, THREE_OF_A_KIND, STRAIGHT, FLUSH, FULL_HOUSE, FOUR_OF_A_KIND, STRAIGHT_FLUSH
    }

    record Hand(Category category, long score, String name, List<String> cards) {}

    private static final String RANKS = "23456789TJQKA";
    private static final String[] PLURAL = {"twos", "threes", "fours", "fives", "sixes", "sevens", "eights", "nines",
            "tens", "jacks", "queens", "kings", "aces"};
    private static final String[] SINGULAR = {"two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "jack", "queen", "king", "ace"};

    /** The best hand from five to seven cards. */
    static Hand best(List<String> cards) {
        if (cards.size() < 5 || cards.size() > 7) throw new IllegalArgumentException("Need 5 to 7 cards");
        Hand best = null;
        int n = cards.size();
        int[] pick = new int[5];
        // Every five-card combination (at most 21).
        for (pick[0] = 0; pick[0] < n; pick[0]++)
            for (pick[1] = pick[0] + 1; pick[1] < n; pick[1]++)
                for (pick[2] = pick[1] + 1; pick[2] < n; pick[2]++)
                    for (pick[3] = pick[2] + 1; pick[3] < n; pick[3]++)
                        for (pick[4] = pick[3] + 1; pick[4] < n; pick[4]++) {
                            List<String> five = new ArrayList<>(5);
                            for (int i : pick) five.add(cards.get(i));
                            Hand hand = five(five);
                            if (best == null || hand.score() > best.score()) best = hand;
                        }
        return best;
    }

    static int rank(String card) {
        int rank = RANKS.indexOf(card.charAt(0));
        if (rank < 0 || card.length() != 2 || "SHDC".indexOf(card.charAt(1)) < 0) {
            throw new IllegalArgumentException("Not a card: " + card);
        }
        return rank;
    }

    /** Scores exactly five cards. */
    private static Hand five(List<String> cards) {
        int[] counts = new int[13];
        boolean flush = true;
        char suit = cards.getFirst().charAt(1);
        for (String card : cards) {
            counts[rank(card)]++;
            if (card.charAt(1) != suit) flush = false;
        }
        // Ranks ordered by how many of each, then by rank: the tiebreak order.
        List<Integer> order = new ArrayList<>();
        for (int c = 4; c >= 1; c--) for (int r = 12; r >= 0; r--) if (counts[r] == c) order.add(r);
        int straightHigh = straightHigh(counts);
        int[] shape = order.stream().mapToInt(r -> counts[r]).toArray();
        Category category;
        int[] kickers;
        if (straightHigh >= 0 && flush) {
            category = Category.STRAIGHT_FLUSH;
            kickers = new int[] {straightHigh};
        } else if (shape[0] == 4) {
            category = Category.FOUR_OF_A_KIND;
            kickers = ints(order);
        } else if (shape[0] == 3 && shape[1] == 2) {
            category = Category.FULL_HOUSE;
            kickers = ints(order);
        } else if (flush) {
            category = Category.FLUSH;
            kickers = ints(order);
        } else if (straightHigh >= 0) {
            category = Category.STRAIGHT;
            kickers = new int[] {straightHigh};
        } else if (shape[0] == 3) {
            category = Category.THREE_OF_A_KIND;
            kickers = ints(order);
        } else if (shape[0] == 2 && shape[1] == 2) {
            category = Category.TWO_PAIR;
            kickers = ints(order);
        } else if (shape[0] == 2) {
            category = Category.PAIR;
            kickers = ints(order);
        } else {
            category = Category.HIGH_CARD;
            kickers = ints(order);
        }
        long score = category.ordinal();
        for (int i = 0; i < 5; i++) score = score * 16 + (i < kickers.length ? kickers[i] + 1 : 0);
        return new Hand(category, score, name(category, kickers), List.copyOf(cards));
    }

    /** The straight's high card (5 for the wheel A-2-3-4-5), or −1. */
    private static int straightHigh(int[] counts) {
        for (int high = 12; high >= 4; high--) {
            boolean run = true;
            for (int r = high; r > high - 5; r--) if (counts[r] != 1) run = false;
            if (run) return high;
        }
        if (counts[12] == 1 && counts[0] == 1 && counts[1] == 1 && counts[2] == 1 && counts[3] == 1) return 3;
        return -1;
    }

    private static int[] ints(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String name(Category category, int[] k) {
        return switch (category) {
            case STRAIGHT_FLUSH -> k[0] == 12 ? "Royal flush" : "Straight flush, " + SINGULAR[k[0]] + " high";
            case FOUR_OF_A_KIND -> "Four " + PLURAL[k[0]];
            case FULL_HOUSE -> "Full house, " + PLURAL[k[0]] + " over " + PLURAL[k[1]];
            case FLUSH -> "Flush, " + SINGULAR[k[0]] + " high";
            case STRAIGHT -> "Straight, " + SINGULAR[k[0]] + " high";
            case THREE_OF_A_KIND -> "Three " + PLURAL[k[0]];
            case TWO_PAIR -> "Two pair, " + PLURAL[k[0]] + " and " + PLURAL[k[1]];
            case PAIR -> "Pair of " + PLURAL[k[0]];
            case HIGH_CARD -> SINGULAR[k[0]].substring(0, 1).toUpperCase() + SINGULAR[k[0]].substring(1) + " high";
        };
    }

    /** For tests and logs: "AS KS" → ["AS", "KS"]. */
    static List<String> cards(String spaced) {
        return Arrays.asList(spaced.trim().split("\\s+"));
    }
}
