package com.p2picks.app.sports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Real provider responses (trimmed fixtures from the recordings) must still match the provider's published specs
 * (dated copies in api/providers), and our parser must accept them. A failure here means the provider or its
 * spec changed: re-download the spec, re-check the mapping in {@link BallDontLieFeed}, and update the fixtures.
 */
class ProviderContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "/providers/balldontlie/nba-2026-11-14.yml, NBAGame, nba-games.json",
        "/providers/balldontlie/nba-2026-11-14.yml, NBAStats, nba-stats.json",
        "/providers/balldontlie/nfl-2026-11-14.yml, NFLGame, nfl-games.json",
        "/providers/balldontlie/nfl-2026-11-14.yml, NFLStats, nfl-stats.json",
    })
    void recordedResponsesMatchTheSavedSpec(String spec, String schema, String fixture) throws IOException {
        JsonSchema validator = schema(spec, schema);
        JsonNode rows = fixture(fixture).path("data");
        assertThat(rows.size()).isPositive();
        for (JsonNode row : rows) {
            assertThat(validator.validate(row)).as("%s row %s", fixture, row.path("id")).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"nba-games.json, NBA", "nfl-games.json, NFL"})
    void everyRecordedGameParses(String fixture, League league) throws IOException {
        for (JsonNode game : fixture(fixture).path("data")) {
            FeedGame parsed = league == League.NBA ? BallDontLieFeed.nba(game) : BallDontLieFeed.nfl(game);
            assertThat(parsed).as("%s game %s", fixture, game.path("id")).isNotNull();
            assertThat(parsed.providerState()).isEqualTo(game.path("status_state").asText());
            if (parsed.status() == GameStatus.FINAL) {
                // Final games report every period, and the periods add up to the final score.
                for (FeedGame.Team team : List.of(parsed.home(), parsed.away())) {
                    assertThat(team.periods()).hasSizeGreaterThanOrEqualTo(4);
                    assertThat(team.periods().stream().mapToInt(Integer::intValue).sum()).isEqualTo(team.score());
                }
            }
            if (parsed.status() == GameStatus.SCHEDULED) assertThat(parsed.home().periods()).isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void recordedStatRowsMapToCanonicalStats() throws IOException {
        List<StatLine> nba = new java.util.ArrayList<>();
        for (JsonNode row : fixture("nba-stats.json").path("data")) nba.add(StatLine.nba(row));
        // A starter with minutes, and a player listed with "0" minutes who didn't play.
        StatLine starter = nba.stream().filter(line -> line.player().lastName().equals("George")).findFirst()
                .orElseThrow();
        assertThat(starter.played()).isTrue();
        assertThat(starter.stats()).containsEntry("points", 14).containsEntry("rebounds", 1)
                .containsEntry("assists", 5).containsEntry("threes", 3).containsEntry("stocks", 1);
        assertThat(starter.player().teamAbbreviation()).isEqualTo("WAS");
        assertThat(nba).anySatisfy(line -> assertThat(line.played()).isFalse());
        for (JsonNode row : fixture("nfl-stats.json").path("data")) {
            StatLine line = StatLine.nfl(row);
            assertThat(line.played()).isTrue();
            assertThat(line.stats().keySet()).containsExactlyInAnyOrder("passing_yards", "rushing_yards",
                    "receiving_yards", "receptions", "touchdowns", "field_goals", "extra_points");
            assertThat(line.stats().get("field_goals")).isEqualTo(row.path("field_goals_made").asInt(0));
            assertThat(line.stats().get("extra_points")).isEqualTo(row.path("extra_points_made").asInt(0));
            assertThat(line.player().position()).isNotBlank();
        }
        assertThat(StatLine.minutesPlayed("00:00")).isFalse();
        assertThat(StatLine.minutesPlayed("0:12")).isTrue();
    }

    static JsonSchema schema(String spec, String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(ProviderContractTest.class.getResourceAsStream(spec), spec)) {
            JsonNode contract = new ObjectMapper(new YAMLFactory()).readTree(input);
            // Known deviation (2026-11-14): real NFL responses send null for fields the spec types as plain values,
            // e.g. passing_yards for a player with no attempts or home_team_ot without overtime; and per-attempt
            // averages (yards_per_*) typed as integers are decimals. Totals stay strict integers.
            if (spec.contains("/nfl-")) allowNulls(contract.path("components").path("schemas"));
            ObjectNode schema = JSON.createObjectNode();
            schema.put("$ref", "#/components/schemas/" + name);
            schema.set("components", contract.path("components"));
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        }
    }

    /** Lets every typed property in these schemas also be null, and averages be decimals. */
    private static void allowNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode properties = object.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(field -> {
                    JsonNode property = properties.get(field);
                    if (field.startsWith("yards_per_") && property.path("type").asText().equals("integer")) {
                        ((ObjectNode) property).put("type", "number");
                    }
                });
            }
            JsonNode type = object.get("type");
            if (type != null && type.isTextual() && !type.asText().equals("null")) {
                object.set("type", JSON.createArrayNode().add(type.asText()).add("null"));
            }
            object.elements().forEachRemaining(ProviderContractTest::allowNulls);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(ProviderContractTest::allowNulls);
        }
    }

    static JsonNode fixture(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                ProviderContractTest.class.getResourceAsStream("/fixtures/balldontlie/" + name), name)) {
            return JSON.readTree(input);
        }
    }
}
