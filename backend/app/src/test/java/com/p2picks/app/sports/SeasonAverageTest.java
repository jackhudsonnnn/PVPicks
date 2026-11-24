package com.p2picks.app.sports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeasonAverageTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void nflSeasonTotalsBecomePerGameAveragesInTheModesStats() throws Exception {
        SeasonAverage average = SeasonAverage.nfl(json.readTree("""
                {"player": {"id": 19}, "games_played": 10, "passing_yards": 2453, "rushing_yards": 402,
                 "receiving_yards": 0, "receptions": 0, "rushing_touchdowns": 5, "receiving_touchdowns": 1}
                """));
        assertThat(average.providerId()).isEqualTo("19");
        assertThat(average.games()).isEqualTo(10);
        assertThat(average.averages()).containsEntry("passing_yards", 245.3).containsEntry("rushing_yards", 40.2)
                .containsEntry("touchdowns", 0.6).containsEntry("receptions", 0.0);
    }

    @Test
    void nbaSeasonAveragesMapToTheModesStatsAndEmptyRowsAreSkipped() throws Exception {
        SeasonAverage average = SeasonAverage.nba(json.readTree("""
                {"player": {"id": 237}, "stats": {"gp": 18, "pts": 27.44, "reb": 7.9, "ast": 8.1, "fg3m": 2.3,
                 "stl": 1.2, "blk": 0.6}}
                """));
        assertThat(average.averages()).containsEntry("points", 27.4).containsEntry("stocks", 1.8)
                .containsEntry("threes", 2.3);
        assertThat(SeasonAverage.nfl(json.readTree("{\"player\": {\"id\": 3}, \"games_played\": 0}"))).isNull();
    }

    @Test
    void seasonsAreNamedByTheYearTheyStart() {
        assertThat(SeasonAverage.seasonOf(LocalDate.of(2026, 11, 24))).isEqualTo(2026);
        assertThat(SeasonAverage.seasonOf(LocalDate.of(2027, 2, 1))).isEqualTo(2026);
    }
}
