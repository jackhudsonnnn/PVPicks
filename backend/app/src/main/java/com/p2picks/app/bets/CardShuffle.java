package com.p2picks.app.bets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * The card games' auditable shuffle (domain rules, "Shuffle audit"): Jackblack's shoe each round and Hold'em's deck
 * each hand. Each comes from a secret: a deck count (Jackblack: random, 1–8; Hold'em: one) and a random 32-byte seed,
 * both fixed when the game is dealt. The ticket shows each
 * round's commitment (a SHA-256 of the secret) from the start; the secrets themselves are revealed only once the game
 * is over, so anyone can check the commitment and replay the shuffle. Nothing about the shoe is sent while it could
 * help.
 *
 * <p>The shuffle is fully specified so a browser can reproduce it (the client's audit does):
 * <ol>
 *   <li>Cards in order: for each deck, suits S H D C, ranks A 2 3 4 5 6 7 8 9 T J Q K ("AS" … "KC").</li>
 *   <li>A random stream of 32-bit big-endian words: block k is SHA-256 of the UTF-8 text
 *       {@code cards-v1|<seed hex>|<pass>|<k>}, each block giving eight words.</li>
 *   <li>A uniform number below {@code bound}: draw words until one is below {@code 2^32 − (2^32 mod bound)}, then
 *       take it mod {@code bound}.</li>
 *   <li>Fisher–Yates: for i from n − 1 down to 1, swap card i with card j = uniform(i + 1).</li>
 * </ol>
 * The top card is the first. Pass 0 is the shoe; if a round ever runs through it, pass 1 (then 2…) continues it.
 * The commitment is the SHA-256 hex of {@code cards-v1|<round>|<decks>|<seed hex>}, rounds counted from 1.
 */
final class CardShuffle {
    static final int MIN_DECKS = 1, MAX_DECKS = 8;
    private static final String VERSION = "cards-v1";
    private static final long TWO_32 = 1L << 32;

    private CardShuffle() {}

    /** One round's secret. Public fields so it's stored with the game's state. */
    static final class Secret {
        public int round;
        public int decks;
        public String seed;

        Secret() {}

        Secret(int round, int decks, String seed) {
            this.round = round;
            this.decks = decks;
            this.seed = seed;
        }

        String commitment() {
            return sha256Hex(VERSION + "|" + round + "|" + decks + "|" + seed);
        }
    }

    /** A fresh secret for round {@code round} (from 1) with a random deck count (Jackblack's shoe). */
    static Secret draw(int round, Random random) {
        return draw(round, MIN_DECKS + random.nextInt(MAX_DECKS - MIN_DECKS + 1), random);
    }

    /** A fresh secret for round {@code round} (from 1) with {@code decks} decks (Hold'em: one). */
    static Secret draw(int round, int decks, Random random) {
        byte[] seed = new byte[32];
        random.nextBytes(seed);
        return new Secret(round, decks, HexFormat.of().formatHex(seed));
    }

    /** The shoe for a secret, top card first; {@code pass} 0 is the shoe itself, later passes continue it. */
    static List<String> cards(Secret secret, int pass) {
        List<String> shoe = new ArrayList<>(secret.decks * 52);
        for (int d = 0; d < secret.decks; d++) {
            for (char suit : "SHDC".toCharArray()) {
                for (char rank : "A23456789TJQK".toCharArray()) shoe.add("" + rank + suit);
            }
        }
        Stream stream = new Stream(secret.seed, pass);
        for (int i = shoe.size() - 1; i > 0; i--) {
            int j = stream.below(i + 1);
            String swap = shoe.get(i);
            shoe.set(i, shoe.get(j));
            shoe.set(j, swap);
        }
        return shoe;
    }

    /** The SHA-256 word stream for one pass of one seed. */
    private static final class Stream {
        private final String prefix;
        private long block;
        private byte[] words = new byte[0];
        private int at;

        Stream(String seed, int pass) {
            prefix = VERSION + "|" + seed + "|" + pass + "|";
        }

        long next() {
            if (at >= words.length) {
                words = sha256(prefix + block++);
                at = 0;
            }
            long word = ((words[at] & 0xFFL) << 24) | ((words[at + 1] & 0xFFL) << 16) | ((words[at + 2] & 0xFFL) << 8)
                    | (words[at + 3] & 0xFFL);
            at += 4;
            return word;
        }

        int below(int bound) {
            long limit = TWO_32 - TWO_32 % bound;
            long word;
            do {
                word = next();
            } while (word >= limit);
            return (int) (word % bound);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }
}
