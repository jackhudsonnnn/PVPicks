package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optionally appends every raw provider response to {@code <dir>/<league>-<New York date>.jsonl}, one JSON object
 * per line with the receive time, request query and body. Off unless {@code PVPICKS_SPORTS_RECORDING_DIR} is set.
 * Recordings never contain the API key, which travels only in a header. They are raw on purpose, so a later
 * change to the mapping can be replayed against real responses.
 */
@Component
class FeedRecorder {
    private static final Logger log = LoggerFactory.getLogger(FeedRecorder.class);

    private final Path directory;
    private final Clock clock;
    private final ObjectMapper json;

    FeedRecorder(@Value("${pvpicks.sports.recording-dir:}") String directory, Clock clock, ObjectMapper json) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory);
        this.clock = clock;
        this.json = json;
    }

    void record(League league, String query, String body) {
        if (directory == null) return;
        try {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("receivedAt", clock.instant().toString());
            line.put("league", league.name());
            line.put("query", query);
            line.put("body", json.readTree(body == null ? "null" : body));
            Files.createDirectories(directory);
            LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneId.of(GameSync.NEW_YORK));
            Files.writeString(directory.resolve(league.name().toLowerCase() + "-" + date + ".jsonl"),
                    json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            // Recording is a debugging aid; it must never break polling.
            log.warn("Could not record a sports feed response: {}", exception.getMessage());
        }
    }
}
