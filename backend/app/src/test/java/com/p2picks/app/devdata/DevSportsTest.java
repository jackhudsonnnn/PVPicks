package com.p2picks.app.devdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.GameSync;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.StatsSync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dev scenarios and chunks: what state they produce, and that the runner feeds it through the real sync. */
class DevSportsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant START = Instant.parse("2026-11-15T01:00:00Z");

    @TempDir
    Path root;

    private void scenario(String name, String body) throws IOException {
        Files.createDirectories(root.resolve("scenarios"));
        Files.writeString(root.resolve("scenarios").resolve(name + ".json"), body);
    }

    private static final String TOUCHDOWN = """
            {"title": "TD", "description": "d", "league": "NFL",
             "games": [{"id": "g1", "startsInMinutes": -60, "status": "LIVE", "period": 4, "clock": "2:00",
                        "home": {"abbreviation": "DET", "score": 17}, "away": {"abbreviation": "GB", "score": 20}}],
             "players": [{"id": "wr1", "game": "g1", "team": "DET", "lastName": "Receiver",
                          "stats": {"receiving_yards": 70, "touchdowns": 0}}],
             "steps": [{"afterSeconds": 60, "games": [{"id": "g1", "home": {"score": 24}}],
                        "players": [{"id": "wr1", "stats": {"touchdowns": 1}}]},
                       {"afterSeconds": 120, "games": [{"id": "g1", "status": "FINAL"}]}]}
            """;

    @Test
    void scenariosApplyStepsAsTimePassesWithDevIdsAndRelativeStarts() throws IOException {
        scenario("td", TOUCHDOWN);
        DevSources.State before = DevSources.scenario(JSON, root, "td", START, Duration.ofSeconds(30));
        FeedGame game = before.games().getFirst();
        assertThat(game.id()).isEqualTo("NFL-DEV-td-g1");
        assertThat(game.startsAt()).isEqualTo(START.minus(Duration.ofMinutes(60)));
        assertThat(game.home().score()).isEqualTo(17);
        assertThat(before.lines().get("DEV-td-g1").getFirst().stats()).containsEntry("touchdowns", 0)
                .containsEntry("passing_yards", 0);

        DevSources.State after = DevSources.scenario(JSON, root, "td", START, Duration.ofSeconds(61));
        assertThat(after.games().getFirst().home().score()).as("steps merge into the game").isEqualTo(24);
        assertThat(after.games().getFirst().home().abbreviation()).as("untouched fields stay").isEqualTo("DET");
        assertThat(after.lines().get("DEV-td-g1").getFirst().stats()).containsEntry("touchdowns", 1)
                .containsEntry("receiving_yards", 70);
        assertThat(DevSources.scenario(JSON, root, "td", START, Duration.ofMinutes(3)).games().getFirst().status())
                .isEqualTo(GameStatus.FINAL);
    }

    @Test
    void scenarioMistakesSayWhatsWrong() throws IOException {
        scenario("bad", TOUCHDOWN.replace("receiving_yards", "recieving_yards"));
        assertThatThrownBy(() -> DevSources.scenario(JSON, root, "bad", START, Duration.ZERO))
                .hasMessageContaining("Unknown NFL stat \"recieving_yards\"");
        scenario("nogame", TOUCHDOWN.replace("\"game\": \"g1\"", "\"game\": \"g9\""));
        assertThatThrownBy(() -> DevSources.scenario(JSON, root, "nogame", START, Duration.ZERO))
                .hasMessageContaining("unknown game");
        assertThat(DevSources.scenarioNames(root)).containsExactly("bad", "nogame");
    }

    @Test
    void chunksReplayRecordedResponsesThroughTheLiveParser() throws IOException {
        Path chunk = root.resolve("chunks").resolve("late");
        Files.createDirectories(chunk.resolve("responses"));
        Files.writeString(chunk.resolve("chunk.json"), """
                {"title": "Late", "description": "", "league": "NBA", "recordedFrom": "2026-11-13T00:30:00Z",
                 "durationSeconds": 600}
                """);
        ObjectNode games = (ObjectNode) JSON.readTree(
                getClass().getResourceAsStream("/fixtures/balldontlie/nba-games.json"));
        ObjectNode early = JSON.createObjectNode().set("body", games.deepCopy());
        ((ObjectNode) early.path("body").path("data").get(0)).put("home_team_score", 50);
        Files.writeString(chunk.resolve("responses").resolve("000000000_games.json"), early.toString());
        Files.writeString(chunk.resolve("responses").resolve("000300000_games.json"),
                JSON.createObjectNode().set("body", games).toString());

        DevSources.State start = DevSources.chunk(JSON, root, "late", START, Duration.ofSeconds(10));
        FeedGame first = start.games().getFirst();
        assertThat(first.providerId()).startsWith("DEV-late-");
        assertThat(first.home().score()).isEqualTo(50);
        assertThat(first.startsAt()).as("shifted so the chunk starts now")
                .isEqualTo(Instant.parse("2026-11-13T00:00:00Z").plus(Duration.between(
                        Instant.parse("2026-11-13T00:30:00Z"), START)));
        assertThat(DevSources.chunk(JSON, root, "late", START, Duration.ofSeconds(301)).games().getFirst()
                .home().score()).isEqualTo(123);
        assertThat(DevSources.chunkLength(JSON, root, "late")).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void theRunnerRecordsDevGamesWithoutTouchingFeedHealthAndKeepsTheLastGoodState() throws IOException {
        scenario("td", TOUCHDOWN);
        GameSync games = mock(GameSync.class);
        StatsSync stats = mock(StatsSync.class);
        DevSportsRunner runner = new DevSportsRunner(root.toString(), JSON, games, stats,
                Clock.fixed(START, ZoneOffset.UTC));
        DevSportsRunner.Run run = runner.start(DevSportsRunner.Kind.SCENARIO, "td");
        verify(games).recordDev(eq(League.NFL), any(), eq(START));
        verify(stats).recordGame(eq("NFL-DEV-td-g1"), any(), eq(START));
        assertThat(run.games).isEqualTo(1);
        assertThat(run.players).isEqualTo(1);

        scenario("td", "{ not json");
        runner.tick();
        assertThat(runner.run(DevSportsRunner.Kind.SCENARIO, "td").error).isNotBlank();
        runner.stop(DevSportsRunner.Kind.SCENARIO, "td");
        assertThat(runner.run(DevSportsRunner.Kind.SCENARIO, "td")).isNull();
        assertThatThrownBy(() -> runner.speed("x", 3.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(0.5, 1.0, 2.0, 4.0, 8.0)).isEqualTo(DevSportsRunner.SPEEDS);
    }
}
