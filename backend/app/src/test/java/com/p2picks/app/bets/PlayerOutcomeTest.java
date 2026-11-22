package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerOutcomeTest {
    private static final Instant FINAL_AT = Instant.parse("2026-11-15T03:00:00Z");
    private static final Instant SETTLED = FINAL_AT.plus(SportsOutcome.GRACE);
    private static final Instant OT = FINAL_AT.minus(Duration.ofMinutes(10));

    private static GameLookup.Game game(GameStatus status) {
        return new GameLookup.Game("NBA-1", League.NBA, status, null, FINAL_AT.minusSeconds(9000),
                status == GameStatus.FINAL ? FINAL_AT : null, new GameLookup.Team("BOS", 100, List.of()),
                new GameLookup.Team("NYK", 99, List.of()));
    }

    /** Stats per player: current value, value before overtime, and when they first reached a number. */
    private static final class Facts implements PlayerOutcome.Facts {
        final Map<String, Integer> now = new HashMap<>();
        final Map<String, Integer> regulation = new HashMap<>();
        final Map<String, Instant> reached = new HashMap<>();
        final Map<String, Boolean> played = new HashMap<>();
        Instant overtime;

        Facts player(String id, int value, int beforeOvertime, Instant reachedAt) {
            now.put(id, value);
            regulation.put(id, beforeOvertime);
            if (reachedAt != null) reached.put(id, reachedAt);
            played.put(id, true);
            return this;
        }

        @Override
        public boolean played(String playerId) {
            return played.getOrDefault(playerId, false);
        }

        @Override
        public int value(String playerId, String stat) {
            return now.getOrDefault(playerId, 0);
        }

        @Override
        public Instant overtimeStart() {
            return overtime;
        }

        @Override
        public int valueBefore(String playerId, String stat, Instant cutoff) {
            return regulation.getOrDefault(playerId, 0);
        }

        @Override
        public Instant firstReached(String playerId, String stat, int target) {
            return reached.get(playerId);
        }
    }

    private static SportsOutcome.Terms terms(String mode, String line, boolean overtime, List<String> choices,
            List<String> players) {
        return new SportsOutcome.Terms(mode, line == null ? null : new BigDecimal(line), overtime, choices,
                "points", players, FINAL_AT.minus(Duration.ofHours(2)));
    }

    private static final List<String> EITHER = List.of("A Player", "B Player");

    @Test
    void eitherOrComparesAfterFinalWashesTiesAndPlayersWhoDidntPlay() {
        var terms = terms("either_or", null, true, EITHER, List.of("a", "b"));
        Facts facts = new Facts().player("a", 30, 30, null).player("b", 25, 25, null);
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.LIVE), null, SETTLED, facts))
                .isEqualTo(new SportsOutcome.Result.Wait());
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.FINAL), null, SETTLED.minusSeconds(1), facts))
                .as("grace period").isEqualTo(new SportsOutcome.Result.Wait());
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.FINAL), null, SETTLED, facts))
                .isEqualTo(new SportsOutcome.Result.Winner("A Player"));
        facts.now.put("b", 30);
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.FINAL), null, SETTLED, facts))
                .isEqualTo(new SportsOutcome.Result.Wash(PlayerOutcome.TIE));
        facts.played.put("b", false);
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.FINAL), null, SETTLED, facts))
                .isEqualTo(new SportsOutcome.Result.Wash(PlayerOutcome.DID_NOT_PLAY));
    }

    @Test
    void overtimeOffUsesTheValuesBeforeOvertime() {
        Facts facts = new Facts().player("a", 30, 22, null).player("b", 25, 24, null);
        facts.overtime = OT;
        assertThat(PlayerOutcome.decide(terms("either_or", null, false, EITHER, List.of("a", "b")),
                game(GameStatus.FINAL), null, SETTLED, facts)).isEqualTo(new SportsOutcome.Result.Winner("B Player"));
        assertThat(PlayerOutcome.decide(terms("either_or", null, true, EITHER, List.of("a", "b")),
                game(GameStatus.FINAL), null, SETTLED, facts)).isEqualTo(new SportsOutcome.Result.Winner("A Player"));
    }

    @Test
    void propHuntIsOverUnderOrPushOnAWholeLine() {
        List<String> choices = List.of("Over 24.5", "Under 24.5");
        Facts facts = new Facts().player("a", 25, 25, null);
        assertThat(PlayerOutcome.decide(terms("prop_hunt", "24.5", true, choices, List.of("a")),
                game(GameStatus.FINAL), null, SETTLED, facts)).isEqualTo(new SportsOutcome.Result.Winner("Over 24.5"));
        facts.now.put("a", 20);
        assertThat(PlayerOutcome.decide(terms("prop_hunt", "24.5", true, choices, List.of("a")),
                game(GameStatus.FINAL), null, SETTLED, facts)).isEqualTo(new SportsOutcome.Result.Winner("Under 24.5"));
        facts.now.put("a", 25);
        assertThat(PlayerOutcome.decide(terms("prop_hunt", "25", true, List.of("Over 25", "Under 25"), List.of("a")),
                game(GameStatus.FINAL), null, SETTLED, facts)).isEqualTo(new SportsOutcome.Result.Wash("Push"));
    }

    @Test
    void kingOfTheHillSettlesOnTheFirstToReachItOrNeitherAfterFinal() {
        List<String> choices = List.of("A Player", "B Player", "Neither");
        Instant early = FINAL_AT.minus(Duration.ofMinutes(60));
        Facts facts = new Facts().player("a", 31, 31, early.plusSeconds(60)).player("b", 30, 30, early);
        var terms = terms("king_of_the_hill", "30", true, choices, List.of("a", "b"));
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.LIVE), null, early, facts))
                .as("settles mid-game").isEqualTo(new SportsOutcome.Result.Winner("B Player"));
        facts.reached.put("a", early);
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.LIVE), null, early, facts))
                .isEqualTo(new SportsOutcome.Result.Wash(PlayerOutcome.SAME_UPDATE));
        Facts nobody = new Facts().player("a", 10, 10, null).player("b", 12, 12, null);
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.LIVE), null, early, nobody))
                .isEqualTo(new SportsOutcome.Result.Wait());
        assertThat(PlayerOutcome.decide(terms, game(GameStatus.FINAL), null, SETTLED, nobody))
                .isEqualTo(new SportsOutcome.Result.Winner("Neither"));
        Facts inOvertime = new Facts().player("a", 31, 29, OT.plusSeconds(30)).player("b", 20, 20, null);
        inOvertime.overtime = OT;
        assertThat(PlayerOutcome.decide(terms("king_of_the_hill", "30", false, choices, List.of("a", "b")),
                game(GameStatus.FINAL), null, SETTLED, inOvertime))
                .as("reached in overtime with overtime off doesn't count").isEqualTo(new SportsOutcome.Result.Winner("Neither"));
    }

    @Test
    void canceledGamesWashEveryPlayerMode() {
        var postponed = new GameLookup.Game("NBA-1", League.NBA, GameStatus.POSTPONED, "canceled",
                FINAL_AT, null, new GameLookup.Team("BOS", null, List.of()), new GameLookup.Team("NYK", null, List.of()));
        assertThat(PlayerOutcome.decide(terms("either_or", null, true, EITHER, List.of("a", "b")), postponed, null,
                SETTLED, new Facts())).isEqualTo(new SportsOutcome.Result.Wash("Game canceled or postponed"));
    }
}
