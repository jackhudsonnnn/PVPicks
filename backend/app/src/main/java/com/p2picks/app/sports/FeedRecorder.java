package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optionally saves every raw provider response as its own pretty-printed JSON file:
 * {@code <dir>/<league>/<New York date>/<UTC timestamp>_<endpoint>.json}, for example
 * {@code nba/2026-11-13/2026-11-13T23-59-59.123Z_games.json}. File names sort in receive order, so a replay reads
 * them in name order; each file also holds the receive time, endpoint, query and HTTP status. Off unless
 * {@code PVPICKS_SPORTS_RECORDING_DIR} is set. Recordings never contain the API key, which travels only in a header.
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
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    void record(League league, String endpoint, String query, int status, String body) {
        if (directory == null) return;
        try {
            Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Map<String, Object> recording = new LinkedHashMap<>();
            recording.put("receivedAt", receivedAt.toString());
            recording.put("league", league.name());
            recording.put("endpoint", endpoint);
            recording.put("query", query);
            recording.put("status", status);
            recording.put("body", json.readTree(body == null ? "null" : body));
            Path day = directory.resolve(league.name().toLowerCase(Locale.ROOT))
                    .resolve(LocalDate.ofInstant(receivedAt, ZoneId.of(GameSync.NEW_YORK)).toString());
            Files.createDirectories(day);
            String stem = receivedAt.toString().replace(':', '-') + "_" + endpoint.replaceAll("[^a-z0-9_]", "_");
            byte[] content = json.writeValueAsBytes(recording);
            for (int attempt = 0; ; attempt++) {
                Path file = day.resolve(stem + (attempt == 0 ? "" : "-" + attempt) + ".json");
                try {
                    Files.write(file, content, StandardOpenOption.CREATE_NEW);
                    return;
                } catch (FileAlreadyExistsException exception) {
                    if (attempt >= 9) throw exception;
                }
            }
        } catch (IOException | RuntimeException exception) {
            // Recording is an audit and replay aid; it must never break polling.
            log.warn("Could not record a sports feed response: {}", exception.getMessage());
        }
    }
}
