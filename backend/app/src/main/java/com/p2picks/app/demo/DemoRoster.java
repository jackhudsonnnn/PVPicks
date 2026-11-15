package com.p2picks.app.demo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The generated demo users. The same seed always gives the same users (names, ids, archetypes and profiles), so a
 * reset rebuilds the same world and links to a demo user keep working.
 */
public final class DemoRoster {
    /** How many demo users the world has, besides the main accounts. */
    public static final int SIZE = 200;
    static final long SEED = 20261114L;

    /** The main accounts, shown first on the sign-in picker. */
    public static final List<String> MAIN = List.of("newbie", "intermediate", "pro", "admin");

    public record DemoUser(UUID id, String username, int hue, DemoProfile.Archetype archetype, DemoProfile profile) {}

    private static final String[] FIRST = {
        "alex", "ben", "cam", "dana", "eli", "fran", "gabe", "hana", "ike", "jo", "kai", "lena", "max", "nia",
        "omar", "pia", "quin", "rae", "sam", "tess", "uma", "vic", "wes", "xan", "yara", "zed", "ari", "bo",
        "cole", "dev", "erin", "finn", "gia", "hal", "ivy", "jay", "kim", "lou", "mo", "ned", "ola", "pat",
        "ray", "sky", "tom", "val", "will", "zoe", "drew", "remy", "nico", "tara", "luca", "mia", "theo", "ren"};
    private static final String[] WORD = {
        "hoops", "buckets", "dunk", "brick", "swish", "blitz", "sack", "gridiron", "redzone", "fadeaway", "clutch",
        "parlay", "lock", "sharp", "fade", "chalk", "dog", "cover", "over", "under", "pick6", "hailmary",
        "boards", "dime", "triple", "bigplay", "endzone", "goat", "mvp", "bench", "rookie", "vet", "hustle"};

    private DemoRoster() {}

    /** Stable id for a username, so the same demo user keeps the same id across resets. */
    public static UUID idFor(String username) {
        return UUID.nameUUIDFromBytes(("p2picks-demo:" + username).getBytes(StandardCharsets.UTF_8));
    }

    public static List<DemoUser> users() {
        return generate(SEED, SIZE);
    }

    static List<DemoUser> generate(long seed, int count) {
        Random random = new Random(seed);
        int totalWeight = 0;
        for (DemoProfile.Archetype archetype : DemoProfile.Archetype.values()) totalWeight += archetype.weight;
        Set<String> taken = new HashSet<>(MAIN);
        List<DemoUser> users = new ArrayList<>(count);
        while (users.size() < count) {
            String username = name(random);
            if (!taken.add(username)) continue;
            int roll = random.nextInt(totalWeight);
            DemoProfile.Archetype archetype = null;
            for (DemoProfile.Archetype candidate : DemoProfile.Archetype.values()) {
                roll -= candidate.weight;
                if (roll < 0) {
                    archetype = candidate;
                    break;
                }
            }
            users.add(new DemoUser(idFor(username), username, 1 + random.nextInt(5), archetype,
                    archetype.profile().jitter(random)));
        }
        return List.copyOf(users);
    }

    /** A handle like "finn_swish", "hoopsdana23" or "clutchkai": 3–15 of a–z, 0–9 and _. */
    private static String name(Random random) {
        String first = FIRST[random.nextInt(FIRST.length)];
        String word = WORD[random.nextInt(WORD.length)];
        String name = switch (random.nextInt(4)) {
            case 0 -> first + "_" + word;
            case 1 -> word + first + (10 + random.nextInt(90));
            case 2 -> word + first;
            default -> first + word + random.nextInt(10);
        };
        return name.length() > 15 ? name.substring(0, 15) : name;
    }
}
