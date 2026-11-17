package com.p2picks.app.demo;

import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.FeedPlayer;
import com.p2picks.app.sports.StatLine;
import com.p2picks.app.sports.StatsSync;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.League;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Games that are always on: three slots (two NBA, one NFL) that each loop through a 15-minute pregame, 75 minutes
 * live and 10 minutes final, staggered so there are live games at any hour. Each loop is a new game
 * ({@code NBA-DEV-demo-<slot>-<loop>}) with its own teams and scores, written through the real dev game sync, so
 * score bets on them lock and settle like any other. A game's state is a pure function of the clock: nothing to
 * save, and a restart picks up where the loop is. See docs/site/demo-data.md.
 */
@Component
@ConditionalOnProperty(name = "pvpicks.demo.enabled", havingValue = "true")
public class DemoGames {
    private static final Logger log = LoggerFactory.getLogger(DemoGames.class);
    static final Instant ANCHOR = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration PREGAME = Duration.ofMinutes(15);
    static final Duration LIVE = Duration.ofMinutes(75);
    static final Duration LOOP = Duration.ofMinutes(100);

    record Slot(String name, League league, Duration offset) {}

    static final List<Slot> SLOTS = List.of(
            new Slot("nba1", League.NBA, Duration.ZERO),
            new Slot("nfl1", League.NFL, Duration.ofMinutes(35)),
            new Slot("nba2", League.NBA, Duration.ofMinutes(65)));

    private record Club(String abbreviation, String city, String name) {}

    private static final List<Club> NBA = List.of(
            new Club("BOS", "Boston", "Celtics"), new Club("LAL", "Los Angeles", "Lakers"),
            new Club("DEN", "Denver", "Nuggets"), new Club("MIL", "Milwaukee", "Bucks"),
            new Club("GSW", "Golden State", "Warriors"), new Club("PHX", "Phoenix", "Suns"),
            new Club("NYK", "New York", "Knicks"), new Club("MIA", "Miami", "Heat"),
            new Club("OKC", "Oklahoma City", "Thunder"), new Club("DAL", "Dallas", "Mavericks"));
    private static final List<Club> NFL = List.of(
            new Club("KC", "Kansas City", "Chiefs"), new Club("BUF", "Buffalo", "Bills"),
            new Club("PHI", "Philadelphia", "Eagles"), new Club("SF", "San Francisco", "49ers"),
            new Club("DET", "Detroit", "Lions"), new Club("BAL", "Baltimore", "Ravens"),
            new Club("DAL", "Dallas", "Cowboys"), new Club("GB", "Green Bay", "Packers"));

    private final GameSync sync;
    private final StatsSync stats;
    private final Clock clock;

    DemoGames(GameSync sync, StatsSync stats, Clock clock) {
        this.sync = sync;
        this.stats = stats;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pvpicks.demo.games.tick:PT10S}", initialDelayString = "PT5S")
    public void tick() {
        Instant now = clock.instant();
        try {
            Map<League, List<FeedGame>> games = new EnumMap<>(League.class);
            for (Slot slot : SLOTS) {
                games.computeIfAbsent(slot.league(), league -> new ArrayList<>()).add(game(slot, loop(slot, now), now));
            }
            games.forEach((league, list) -> sync.recordDev(league, list, now));
            // Box scores for the player modes, from the same minute-by-minute scoring.
            for (Slot slot : SLOTS) {
                long n = loop(slot, now);
                FeedGame game = game(slot, n, now);
                if (game.status() == GameStatus.SCHEDULED) continue;
                stats.recordGame(game.id(), box(slot, n, now), now);
            }
        } catch (RuntimeException exception) {
            log.warn("Demo games tick failed", exception);
        }
    }

    static long loop(Slot slot, Instant at) {
        return Math.floorDiv(Duration.between(ANCHOR.plus(slot.offset()), at).toSeconds(), LOOP.toSeconds());
    }

    private static final String[] FIRST = {"Jalen", "Marcus", "Tyrese", "Devin", "Isaiah", "Caleb", "Darius",
        "Malik", "Andre", "Jordan", "Trey", "Cam", "Xavier", "Zion", "Ty", "Nico"};
    private static final String[] LAST = {"Holloway", "Brightwater", "Okafor", "Lindqvist", "Ramsey", "Delacroix",
        "Whitfield", "Abernathy", "Castellano", "Mbeki", "Sorensen", "Prescott", "Vasquez", "Thornbury", "Kowalski"};
    private static final String[] NBA_POSITIONS = {"G", "G", "F", "F", "C", "G", "F", "C"};
    private static final String[] NFL_POSITIONS = {"QB", "RB", "WR", "WR", "TE", "RB", "WR", "K"};
    /** Each player's share of the team's production, best first. */
    private static final double[] SHARE = {0.24, 0.18, 0.15, 0.12, 0.10, 0.09, 0.07, 0.05};

    /** A club's demo roster: the same eight made-up players every game, with stable ids. */
    static List<FeedPlayer> roster(League league, String club) {
        Random random = new Random(club.hashCode() * 31L + league.ordinal());
        List<FeedPlayer> players = new ArrayList<>();
        for (int i = 0; i < SHARE.length; i++) {
            players.add(new FeedPlayer(league, "DEV-demo-" + club + "-" + (i + 1), FIRST[random.nextInt(FIRST.length)],
                    LAST[random.nextInt(LAST.length)], (league == League.NBA ? NBA_POSITIONS : NFL_POSITIONS)[i], club));
        }
        return players;
    }

    /** Both teams' lines in loop {@code n} as it stands at {@code at}; empty before tip-off. */
    static List<StatLine> box(Slot slot, long n, Instant at) {
        Instant tipOff = ANCHOR.plus(slot.offset()).plus(LOOP.multipliedBy(n)).plus(PREGAME);
        if (at.isBefore(tipOff)) return List.of();
        Random random = new Random(slot.name().hashCode() * 1_000_003L + n);
        List<Club> clubs = slot.league() == League.NBA ? NBA : NFL;
        Club home = clubs.get(random.nextInt(clubs.size()));
        Club away;
        do {
            away = clubs.get(random.nextInt(clubs.size()));
        } while (away.equals(home));
        int minutes = (int) LIVE.toMinutes();
        int[][] scored = scoring(slot.league(), random, minutes);
        int upTo = (int) Math.min(Duration.between(tipOff, at).toMinutes(), minutes);
        String providerId = "DEV-demo-" + slot.name() + "-" + n;
        List<StatLine> lines = new ArrayList<>();
        Club[] sides = {home, away};
        for (int side = 0; side < 2; side++) {
            int points = 0;
            int touchdowns = 0;
            int fieldGoals = 0;
            for (int m = 0; m < upTo; m++) {
                int minute = scored[side][m];
                points += minute;
                // A minute's points are a touchdown with its extra point (7), a field goal (3) and/or a safety (2),
                // so the kicker's line explains the score the way Scorcerer reads it.
                if (minute >= 6) {
                    touchdowns++;
                    minute -= 7;
                }
                if (minute >= 3) fieldGoals++;
            }
            List<FeedPlayer> players = roster(slot.league(), sides[side].abbreviation());
            for (int i = 0; i < players.size(); i++) {
                double share = SHARE[i];
                Map<String, Integer> line = new LinkedHashMap<>();
                if (slot.league() == League.NBA) {
                    int playerPoints = (int) Math.round(points * share);
                    line.put("points", playerPoints);
                    line.put("rebounds", (int) Math.round(upTo * share * 0.6));
                    line.put("assists", (int) Math.round(upTo * share * 0.4));
                    line.put("threes", playerPoints / 9);
                    line.put("stocks", (int) Math.round(upTo * share * 0.12));
                } else {
                    String position = players.get(i).position();
                    line.put("passing_yards", position.equals("QB") ? points * 8 + upTo * 2 : 0);
                    line.put("rushing_yards", position.equals("RB") ? (int) Math.round(upTo * share * 6) : 0);
                    boolean catches = position.equals("WR") || position.equals("TE") || position.equals("RB");
                    line.put("receiving_yards", catches ? (int) Math.round(upTo * share * 8) : 0);
                    line.put("receptions", catches ? (int) Math.round(upTo * share * 0.6) : 0);
                    // Touchdowns go round-robin to the backs and receivers (players 2-5).
                    int scorers = 4;
                    line.put("touchdowns", i >= 1 && i <= scorers ? touchdowns / scorers
                            + (touchdowns % scorers >= i ? 1 : 0) : 0);
                    boolean kicker = position.equals("K");
                    line.put("field_goals", kicker ? fieldGoals : 0);
                    line.put("extra_points", kicker ? touchdowns : 0);
                }
                lines.add(new StatLine(slot.league(), providerId, players.get(i), true, line));
            }
        }
        return lines;
    }

    /** The slot's game in loop {@code n}, as it stands at {@code at}. */
    static FeedGame game(Slot slot, long n, Instant at) {
        Instant start = ANCHOR.plus(slot.offset()).plus(LOOP.multipliedBy(n));
        Instant tipOff = start.plus(PREGAME);
        Random random = new Random(slot.name().hashCode() * 1_000_003L + n);
        List<Club> clubs = slot.league() == League.NBA ? NBA : NFL;
        Club home = clubs.get(random.nextInt(clubs.size()));
        Club away;
        do {
            away = clubs.get(random.nextInt(clubs.size()));
        } while (away.equals(home));
        int minutes = (int) LIVE.toMinutes();
        int[][] scored = scoring(slot.league(), random, minutes);
        String providerId = "DEV-demo-" + slot.name() + "-" + n;
        long played = Duration.between(tipOff, at).toMinutes();
        if (at.isBefore(tipOff)) {
            return new FeedGame(slot.league(), providerId, GameStatus.SCHEDULED, tipOff, null, null,
                    team(home, null, null), team(away, null, null), null);
        }
        boolean over = played >= minutes;
        int upTo = (int) Math.min(played, minutes);
        int quarter = Math.min(4, 1 + upTo * 4 / minutes);
        double quarterLength = minutes / 4.0;
        double intoQuarter = upTo - (quarter - 1) * quarterLength;
        int clockMinutes = slot.league() == League.NBA ? 12 : 15;
        int secondsLeft = (int) Math.max(0, Math.round(clockMinutes * 60 * (1 - intoQuarter / quarterLength)));
        String gameClock = over ? null : "%d:%02d".formatted(secondsLeft / 60, secondsLeft % 60);
        return new FeedGame(slot.league(), providerId, over ? GameStatus.FINAL : GameStatus.LIVE, tipOff,
                over ? 4 : quarter, gameClock, team(home, scored[0], upTo), team(away, scored[1], upTo), null);
    }

    /**
     * Points per minute for home and away. NBA about 115 a side, NFL about 24, and never tied at the end (the demo
     * has no overtime): the home side gets a last-minute point or field goal.
     */
    private static int[][] scoring(League league, Random random, int minutes) {
        int[][] scored = new int[2][minutes];
        for (int side = 0; side < 2; side++) {
            for (int m = 0; m < minutes; m++) {
                if (league == League.NBA) {
                    scored[side][m] = new int[] {0, 1, 1, 2, 2, 2, 3}[random.nextInt(7)];
                } else if (random.nextDouble() < 0.06) {
                    double kind = random.nextDouble();
                    scored[side][m] = kind < 0.6 ? 7 : kind < 0.95 ? 3 : 2;
                }
            }
        }
        int home = 0;
        int away = 0;
        for (int m = 0; m < minutes; m++) {
            home += scored[0][m];
            away += scored[1][m];
        }
        if (home == away) scored[0][minutes - 1] += league == League.NBA ? 1 : 3;
        return scored;
    }

    private static FeedGame.Team team(Club club, int[] scored, Integer upTo) {
        if (scored == null) return new FeedGame.Team(club.abbreviation(), club.name(), club.city(), null, List.of());
        int minutes = scored.length;
        List<Integer> periods = new ArrayList<>();
        int total = 0;
        for (int quarter = 0; quarter < 4; quarter++) {
            int from = quarter * minutes / 4;
            int to = (quarter + 1) * minutes / 4;
            if (from >= upTo && quarter > 0) break;
            int points = 0;
            for (int m = from; m < Math.min(to, upTo); m++) points += scored[m];
            periods.add(points);
            total += points;
        }
        return new FeedGame.Team(club.abbreviation(), club.name(), club.city(), total, periods);
    }
}
