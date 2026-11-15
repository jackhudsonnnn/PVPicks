package com.p2picks.app.demo;

import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.League;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
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
    private final Clock clock;

    DemoGames(GameSync sync, Clock clock) {
        this.sync = sync;
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
        } catch (RuntimeException exception) {
            log.warn("Demo games tick failed", exception);
        }
    }

    static long loop(Slot slot, Instant at) {
        return Math.floorDiv(Duration.between(ANCHOR.plus(slot.offset()), at).toSeconds(), LOOP.toSeconds());
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
