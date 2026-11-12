package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TickerContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void tickerContainsOnlySportsSnapshots() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("TickerSnapshot");
        ObjectNode snapshot = JSON.createObjectNode();
        ObjectNode game = snapshot.putArray("games").addObject();
        game.put("id", "game-1");
        game.put("league", "NBA");
        game.put("status", "LIVE");
        game.put("startsAt", "2026-11-11T23:00:00Z");
        game.put("observedAt", "2026-11-12T00:05:00Z");
        game.put("clock", "03:20");
        game.putObject("home").put("abbreviation", "BOS").put("score", 82);
        game.putObject("away").put("abbreviation", "NYK").put("score", 78);
        assertThat(schema.validate(snapshot)).isEmpty();

        game.put("tableId", "private-table");
        assertThat(schema.validate(snapshot)).isNotEmpty();
        game.remove("tableId");
        game.put("betId", "private-bet");
        assertThat(schema.validate(snapshot)).isNotEmpty();
        game.remove("betId");
        game.withObject("home").put("score", -1);
        assertThat(schema.validate(snapshot)).isNotEmpty();
    }

    @Test
    void emptySnapshotAndUnknownScoresAreAllowed() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("TickerSnapshot");
        ObjectNode snapshot = JSON.createObjectNode();
        assertThat(schema.validate(snapshot)).isNotEmpty();
        snapshot.putArray("games");
        assertThat(schema.validate(snapshot)).isEmpty();

        ObjectNode game = snapshot.withArray("games").addObject();
        game.put("id", "game-2");
        game.put("league", "NFL");
        game.put("status", "SCHEDULED");
        game.put("startsAt", "2026-11-12T00:00:00Z");
        game.put("observedAt", "2026-11-11T19:00:00Z");
        game.putObject("home").put("abbreviation", "NE").putNull("score");
        game.putObject("away").put("abbreviation", "BUF").putNull("score");
        assertThat(schema.validate(snapshot)).isEmpty();
    }
}
