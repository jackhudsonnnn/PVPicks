package com.p2picks.app.devdata;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Dev page's API: list scenarios and chunks with what's running, and start, stop, pause or change speed. Only
 * registered when dev sports data is on, so these paths 404 otherwise. Any signed-in player may use them in dev.
 */
@RestController
@ConditionalOnExpression("!'${pvpicks.dev-sports.dir:}'.isBlank()")
class DevSportsController {
    private final DevSportsRunner runner;

    DevSportsController(DevSportsRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/dev/sports")
    ResponseEntity<?> list() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("speeds", DevSportsRunner.SPEEDS);
        List<Map<String, Object>> scenarios = new ArrayList<>();
        List<Map<String, Object>> chunks = new ArrayList<>();
        try {
            for (String name : DevSources.scenarioNames(runner.root())) {
                scenarios.add(view(DevSportsRunner.Kind.SCENARIO, name, null));
            }
            for (String name : DevSources.chunkNames(runner.root())) {
                Duration length;
                try {
                    length = DevSources.chunkLength(runner.json(), runner.root(), name);
                } catch (Exception unreadable) {
                    length = Duration.ZERO;
                }
                chunks.add(view(DevSportsRunner.Kind.CHUNK, name, length));
            }
        } catch (java.io.IOException exception) {
            return error(500, "dev_sports_unreadable", "The dev sports folder can't be read.");
        }
        body.put("scenarios", scenarios);
        body.put("chunks", chunks);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/dev/sports/{kind}/{name}/{action}")
    ResponseEntity<?> act(@PathVariable String kind, @PathVariable String name, @PathVariable String action,
            @RequestBody(required = false) JsonNode input) {
        DevSportsRunner.Kind parsed = switch (kind) {
            case "scenarios" -> DevSportsRunner.Kind.SCENARIO;
            case "chunks" -> DevSportsRunner.Kind.CHUNK;
            default -> null;
        };
        if (parsed == null || !DevSources.NAME.matcher(name).matches()) return error(404, "not_found", "Not found.");
        try {
            boolean exists = parsed == DevSportsRunner.Kind.SCENARIO
                    ? DevSources.scenarioNames(runner.root()).contains(name)
                    : DevSources.chunkNames(runner.root()).contains(name);
            if (!exists) return error(404, "not_found", "No such " + kind.substring(0, kind.length() - 1) + ".");
        } catch (java.io.IOException exception) {
            return error(500, "dev_sports_unreadable", "The dev sports folder can't be read.");
        }
        switch (action) {
            case "start" -> runner.start(parsed, name);
            case "stop" -> runner.stop(parsed, name);
            case "pause", "resume" -> {
                if (parsed != DevSportsRunner.Kind.CHUNK) return error(400, "bad_request", "Only chunks pause.");
                runner.pause(name, action.equals("pause"));
            }
            case "speed" -> {
                if (parsed != DevSportsRunner.Kind.CHUNK || input == null || !input.path("speed").isNumber()) {
                    return error(400, "bad_request", "A chunk speed is required.");
                }
                try {
                    runner.speed(name, input.path("speed").asDouble());
                } catch (IllegalArgumentException invalid) {
                    return error(400, "bad_request", invalid.getMessage());
                }
            }
            default -> {
                return error(404, "not_found", "Unknown action.");
            }
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private Map<String, Object> view(DevSportsRunner.Kind kind, String name, Duration length) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        try {
            DevSources.Info info = kind == DevSportsRunner.Kind.SCENARIO
                    ? DevSources.scenarioInfo(runner.json(), runner.root(), name)
                    : DevSources.chunkInfo(runner.json(), runner.root(), name);
            view.put("title", info.title());
            view.put("description", info.description());
            view.put("league", info.league().name());
        } catch (Exception unreadable) {
            view.put("title", name);
            view.put("description", "");
            view.put("league", null);
            view.put("error", "Can't read this file: " + unreadable.getMessage());
        }
        if (length != null) view.put("lengthSeconds", length.toSeconds());
        DevSportsRunner.Run run = runner.run(kind, name);
        view.put("running", run != null);
        if (run != null) {
            view.put("startedAt", run.startedAt.toString());
            view.put("games", run.games);
            view.put("players", run.players);
            if (run.error != null) view.put("error", run.error);
            if (kind == DevSportsRunner.Kind.CHUNK) {
                view.put("positionSeconds", run.position.toSeconds());
                view.put("speed", run.speed);
                view.put("paused", run.paused);
            }
        }
        return view;
    }
}
