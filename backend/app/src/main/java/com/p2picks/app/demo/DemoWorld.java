package com.p2picks.app.demo;

import com.p2picks.app.security.SignInCodes;
import com.p2picks.app.demo.DemoRoster.DemoUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Writes the demo world into an empty database: the main accounts, the demo users, follows, blocks, tables and
 * invitations. Seeded state is written directly (backdated) rather than through the services, and is kept
 * consistent with what the services would have produced. Same seed, same world. See docs/site/demo-data.md.
 */
final class DemoWorld {
    static final UUID NEWBIE = DemoRoster.idFor("newbie");
    static final UUID INTERMEDIATE = DemoRoster.idFor("intermediate");
    static final UUID PRO = DemoRoster.idFor("pro");
    static final UUID ADMIN = DemoRoster.idFor("admin");

    /** Every demo account's password. Public on purpose: the demo exists only in the dev profile. */
    static final String PASSWORD = "demo";

    /** The pro's big table: he hosts, and the whole crowd sits at it. A fixed id, so the bots and history find it. */
    static final UUID CROWD_TABLE = UUID.nameUUIDFromBytes("p2picks-demo-table:crowd".getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
    static final String CROWD_TABLE_NAME = "Mosh Pit";

    static final int PRO_FRIENDS = 60;
    static final int INTERMEDIATE_FRIENDS = 9;

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final String[] TABLE_NAMES = {
        "Game Night", "Sunday Sweats", "Hoop Dreams", "Couch Coaches", "Bad Beats", "Office Pool", "The Lock Room",
        "Parlay Palace", "Fantasy Flops", "Overtime", "Buzzer Beaters", "Chalk Talk", "Red Zone", "Dog House",
        "Garbage Time", "Sharps Only", "Tailgate", "Free Throws", "Pick Six", "Fourth & Long", "Bench Mob"};
    private static final String[] EMOJIS = {"🏀", "🏈", "🔥", "🎲", "🍻", "🐐", "💸", "🎯", "🏆", "🍕", "🦴", "⏱️"};

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final Clock clock;

    private final Random random = new Random(DemoRoster.SEED + 1);
    private final Set<String> codes = new HashSet<>();
    private final Set<List<UUID>> follows = new LinkedHashSet<>();
    private int tableIndex;

    DemoWorld(JdbcTemplate jdbc, PasswordEncoder passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.clock = clock;
    }

    DemoReset.Summary seed() {
        Instant now = clock.instant();
        List<DemoUser> bots = DemoRoster.regulars();
        // The picker signs in without a password. Players have none (they sign in with a code); the shared, public
        // demo password is the admin's, for the admin sign-in form. Dev only: production has no demo accounts.
        String hash = passwords.encode(PASSWORD);
        insertUsers(bots, hash, now);

        List<UUID> pool = new ArrayList<>(bots.stream().map(DemoUser::id).toList());
        Collections.shuffle(pool, random);

        // Pro: 60 friends, 15 one-way followers, 5 he follows who haven't followed back.
        // (His 60th friend is intermediate, below.)
        int demoFriends = PRO_FRIENDS - 1;
        List<UUID> proFriends = pool.subList(0, demoFriends);
        proFriends.forEach(friend -> friends(PRO, friend));
        pool.subList(demoFriends, demoFriends + 15).forEach(fan -> follow(fan, PRO));
        pool.subList(demoFriends + 15, demoFriends + 20).forEach(idol -> follow(PRO, idol));
        List<UUID> strangers = pool.subList(demoFriends + 20, pool.size());

        // Intermediate: 9 friends, pro and 8 demo users (some shared with pro).
        List<UUID> intermediateFriends = new ArrayList<>(proFriends.subList(0, 5));
        intermediateFriends.addAll(strangers.subList(0, 3));
        friends(INTERMEDIATE, PRO);
        intermediateFriends.forEach(friend -> friends(INTERMEDIATE, friend));

        // Demo users follow each other, following back per their profile.
        for (DemoUser bot : bots) {
            for (int i = 0; i < 6; i++) {
                DemoUser other = bots.get(random.nextInt(bots.size()));
                if (other.id().equals(bot.id())) continue;
                follow(bot.id(), other.id());
                if (random.nextDouble() < other.profile().followBackChance()) follow(other.id(), bot.id());
            }
        }

        List<UUID> tableMates = new ArrayList<>(strangers.subList(3, 40));
        int tables = 0;
        // Intermediate's few tables: Game Night (pro hosts), one of his own, one a friend hosts.
        UUID gameNight = table("Game Night", PRO, now.minus(Duration.ofDays(88)),
                concat(List.of(INTERMEDIATE), proFriends.subList(0, 4)));
        table(null, INTERMEDIATE, now.minus(Duration.ofDays(40)), intermediateFriends.subList(3, 7));
        table(null, intermediateFriends.get(0), now.minus(Duration.ofDays(30)),
                concat(List.of(INTERMEDIATE, PRO), proFriends.subList(5, 8)));
        tables += 3;

        // Pro's other tables: 5 more he hosts, 8 friends host (with people he doesn't know).
        List<UUID> proTables = new ArrayList<>(List.of(gameNight));
        for (int i = 0; i < 5; i++) {
            proTables.add(table(null, PRO, now.minus(Duration.ofDays(85 - i * 12)), sample(proFriends, 3 + random.nextInt(6))));
        }
        List<UUID> inTablesWithPro = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID host = proFriends.get(10 + i);
            List<UUID> others = sample(tableMates, 2 + random.nextInt(3));
            inTablesWithPro.addAll(others);
            proTables.add(table(null, host, now.minus(Duration.ofDays(80 - i * 9)),
                    concat(List.of(PRO), sample(proFriends, 2 + random.nextInt(4)), others)));
        }
        tables += 13;

        // Blocks: three people in his tables block pro; pro blocks two table-mates and two others.
        List<UUID> mates = new ArrayList<>(new LinkedHashSet<>(inTablesWithPro));
        mates.removeAll(proFriends);
        block(mates.get(0), PRO);
        block(mates.get(1), PRO);
        block(mates.get(2), PRO);
        block(PRO, mates.get(3));
        block(PRO, mates.get(4));
        block(PRO, strangers.get(50));
        block(PRO, strangers.get(51));

        // Two invitations waiting for pro, to tables his friends host.
        for (int i = 0; i < 2; i++) {
            UUID host = proFriends.get(20 + i);
            UUID table = table(null, host, now.minus(Duration.ofDays(10 + i)), sample(strangers.subList(60, 100), 3));
            jdbc.update("""
                    INSERT INTO table_invites (id, table_id, from_id, user_id, status, created_at)
                    VALUES (?, ?, ?, ?, 'PENDING', ?)
                    """, UUID.randomUUID(), table, host, PRO, Timestamp.from(now.minus(Duration.ofHours(3 + i * 20))));
            tables++;
        }

        crowd(hash, now);
        tables++;

        writeFollows(now);
        int blocks = jdbc.queryForObject("SELECT count(*) FROM blocks", Integer.class);
        return new DemoReset.Summary(DemoRoster.SIZE + DemoRoster.MAIN.size(), follows.size(), blocks, tables);
    }

    /**
     * The crowd and the pro's big table, Mosh Pit: the pro hosts, all 50 maniacs are members. They follow each other
     * a lot but aren't the pro's friends, so his friend count stays as documented. Seeded last from its own random
     * source, so everything above is the same world it was before the crowd existed. No history: the bots fill it.
     */
    private void crowd(String hash, Instant now) {
        Random crowdRandom = new Random(DemoRoster.SEED + 2);
        List<DemoUser> crowd = DemoRoster.crowd();
        List<Object[]> rows = new ArrayList<>();
        for (DemoUser bot : crowd) {
            rows.add(new Object[] {bot.id(), bot.username(), bot.hue(), SignInCodes.NO_PASSWORD,
                    Timestamp.from(now.minus(Duration.ofDays(20 + crowdRandom.nextInt(60))))});
        }
        jdbc.batchUpdate("""
                INSERT INTO users (id, username, hue, password_hash, role, status, created_at)
                VALUES (?, ?, ?, ?, 'PLAYER', 'ACTIVE', ?)
                """, rows);
        for (DemoUser bot : crowd) {
            for (int i = 0; i < 10; i++) {
                DemoUser other = crowd.get(crowdRandom.nextInt(crowd.size()));
                follow(bot.id(), other.id());
                if (crowdRandom.nextDouble() < other.profile().followBackChance()) follow(other.id(), bot.id());
            }
        }
        Instant created = now.minus(Duration.ofDays(4));
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, created_at) VALUES (?, ?, '🔥', ?, ?)
                """, CROWD_TABLE, CROWD_TABLE_NAME, code(crowdRandom), Timestamp.from(created));
        jdbc.update("INSERT INTO table_members (table_id, user_id, role, joined_at) VALUES (?, ?, 'HOST', ?)",
                CROWD_TABLE, PRO, Timestamp.from(created));
        jdbc.batchUpdate("INSERT INTO table_members (table_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)",
                crowd.stream().map(bot -> new Object[] {CROWD_TABLE, bot.id(),
                        Timestamp.from(created.plus(Duration.ofMinutes(5 + crowdRandom.nextInt(72 * 60))))}).toList());
    }

    private void insertUsers(List<DemoUser> bots, String hash, Instant now) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {NEWBIE, "newbie", 1, "PLAYER", Timestamp.from(now.minus(Duration.ofMinutes(5)))});
        rows.add(new Object[] {INTERMEDIATE, "intermediate", 2, "PLAYER", Timestamp.from(now.minus(Duration.ofDays(45)))});
        rows.add(new Object[] {PRO, "pro", 3, "PLAYER", Timestamp.from(now.minus(Duration.ofDays(120)))});
        rows.add(new Object[] {ADMIN, "admin", 4, "ADMIN", Timestamp.from(now.minus(Duration.ofDays(120)))});
        for (DemoUser bot : bots) {
            rows.add(new Object[] {bot.id(), bot.username(), bot.hue(), "PLAYER",
                    Timestamp.from(now.minus(Duration.ofDays(100 + random.nextInt(100))))});
        }
        jdbc.batchUpdate("""
                INSERT INTO users (id, username, hue, password_hash, role, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, rows.stream().map(row -> new Object[] {row[0], row[1], row[2],
                        "ADMIN".equals(row[3]) ? hash : SignInCodes.NO_PASSWORD, row[3], row[4]}).toList());
    }

    private void follow(UUID follower, UUID followee) {
        if (!follower.equals(followee)) follows.add(List.of(follower, followee));
    }

    private void friends(UUID a, UUID b) {
        follow(a, b);
        follow(b, a);
    }

    /** Blocking removes follows both ways, as the block action does. */
    private void block(UUID blocker, UUID blocked) {
        follows.remove(List.of(blocker, blocked));
        follows.remove(List.of(blocked, blocker));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                blocker, blocked, Timestamp.from(clock.instant().minus(Duration.ofDays(1 + random.nextInt(30)))));
    }

    private void writeFollows(Instant now) {
        jdbc.batchUpdate("INSERT INTO follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)",
                follows.stream().map(pair -> new Object[] {pair.get(0), pair.get(1),
                        Timestamp.from(now.minus(Duration.ofDays(1 + random.nextInt(100))))}).toList());
    }

    /** A table with its host first (joined at creation) and members joining over the following days. */
    private UUID table(String name, UUID host, Instant created, List<UUID> members) {
        UUID id = UUID.randomUUID();
        String tableName = name != null ? name : TABLE_NAMES[1 + (tableIndex++ % (TABLE_NAMES.length - 1))];
        jdbc.update("""
                INSERT INTO tables (id, name, emoji, quickpick_code, created_at) VALUES (?, ?, ?, ?, ?)
                """, id, tableName, EMOJIS[random.nextInt(EMOJIS.length)], code(), Timestamp.from(created));
        jdbc.update("INSERT INTO table_members (table_id, user_id, role, joined_at) VALUES (?, ?, 'HOST', ?)",
                id, host, Timestamp.from(created));
        List<Object[]> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>(Set.of(host));
        for (UUID member : members) {
            if (!seen.add(member)) continue;
            rows.add(new Object[] {id, member,
                    Timestamp.from(created.plus(Duration.ofHours(1 + random.nextInt(72))))});
        }
        jdbc.batchUpdate("INSERT INTO table_members (table_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', ?)",
                rows);
        return id;
    }

    private String code() {
        return code(random);
    }

    private String code(Random random) {
        while (true) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            if (codes.add(code.toString())) return code.toString();
        }
    }

    private List<UUID> sample(List<UUID> from, int count) {
        List<UUID> copy = new ArrayList<>(from);
        Collections.shuffle(copy, random);
        return copy.subList(0, Math.min(count, copy.size()));
    }

    @SafeVarargs
    private static List<UUID> concat(List<UUID>... lists) {
        List<UUID> all = new ArrayList<>();
        for (List<UUID> list : lists) all.addAll(list);
        return all;
    }
}
