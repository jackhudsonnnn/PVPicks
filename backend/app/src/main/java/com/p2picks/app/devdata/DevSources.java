package com.p2picks.app.devdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.sports.FeedGame;
import com.p2picks.app.sports.FeedPlayer;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.ProviderParsing;
import com.p2picks.app.sports.StatLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reading dev sports data: scenario files (hand-written, live-edited) and recorded chunks (cut from real
 * recordings). Both produce games and player lines in the canonical feed shape, with `DEV-` provider ids and start
 * times relative to when the run started, so they show up today alongside real games.
 */
final class DevSources {
    /** Names become part of game ids, so they're limited to what provider ids allow. */
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,40}");
    private static final Pattern RESPONSE = Pattern.compile("(\\d+)_(games|stats)(?:_(\\w+))?\\.json");

    record Info(String name, String title, String description, League league) {}

    /** What a source contributes at a moment: games, and each game's player lines. */
    record State(League league, List<FeedGame> games, Map<String, List<StatLine>> lines) {}

    private DevSources() {}

    // ---------------------------------------------------------------- Scenarios

    static Path scenarioFile(Path root, String name) {
        return root.resolve("scenarios").resolve(name + ".json");
    }

    static List<String> scenarioNames(Path root) throws IOException {
        Path dir = root.resolve("scenarios");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString()).filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - 5)).filter(name -> NAME.matcher(name).matches())
                    .sorted().toList();
        }
    }

    static Info scenarioInfo(ObjectMapper json, Path root, String name) throws IOException {
        JsonNode file = json.readTree(scenarioFile(root, name).toFile());
        return new Info(name, text(file, "title", name), text(file, "description", ""), league(file));
    }

    /**
     * A scenario's state `elapsed` after its run started: the base `games` and `players`, then every step whose
     * `afterSeconds` has passed, merged in order by id. Re-read on every tick, so saving the file changes the data.
     */
    static State scenario(ObjectMapper json, Path root, String name, Instant startedAt, Duration elapsed)
            throws IOException {
        JsonNode file = json.readTree(scenarioFile(root, name).toFile());
        League league = league(file);
        Map<String, ObjectNode> games = byId(file.path("games"), "games");
        Map<String, ObjectNode> players = byId(file.path("players"), "players");
        List<JsonNode> steps = new ArrayList<>();
        file.path("steps").forEach(steps::add);
        steps.sort(Comparator.comparingDouble(step -> step.path("afterSeconds").asDouble()));
        for (JsonNode step : steps) {
            if (step.path("afterSeconds").asDouble() > elapsed.toMillis() / 1000.0) break;
            merge(games, step.path("games"), "step games");
            merge(players, step.path("players"), "step players");
        }
        String prefix = "DEV-" + name + "-";
        List<FeedGame> feedGames = new ArrayList<>();
        Map<String, String> gameIds = new LinkedHashMap<>();
        for (ObjectNode game : games.values()) {
            String key = game.path("id").asText();
            FeedGame feed = game(league, prefix + key, game, startedAt);
            feedGames.add(feed);
            gameIds.put(key, feed.providerId());
        }
        Map<String, List<StatLine>> lines = new LinkedHashMap<>();
        Set<String> known = StatLine.STATS.get(league).stream().map(StatLine.Stat::key).collect(Collectors.toSet());
        for (ObjectNode player : players.values()) {
            String key = player.path("id").asText();
            String game = gameIds.get(player.path("game").asText());
            if (game == null) throw new IllegalArgumentException("Player " + key + " names an unknown game");
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (StatLine.Stat stat : StatLine.STATS.get(league)) stats.put(stat.key(), 0);
            player.path("stats").properties().forEach(entry -> {
                if (!known.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown " + league + " stat \"" + entry.getKey()
                            + "\" (use " + String.join(", ", known) + ")");
                }
                stats.put(entry.getKey(), entry.getValue().asInt());
            });
            FeedPlayer feedPlayer = new FeedPlayer(league, prefix + key, text(player, "firstName", ""),
                    text(player, "lastName", key), text(player, "position", null), text(player, "team", null));
            lines.computeIfAbsent(game, ignored -> new ArrayList<>()).add(new StatLine(league, game, feedPlayer,
                    player.path("played").asBoolean(true), stats));
        }
        return new State(league, List.copyOf(feedGames), lines);
    }

    private static FeedGame game(League league, String providerId, JsonNode game, Instant startedAt) {
        GameStatus status = GameStatus.valueOf(text(game, "status", "SCHEDULED"));
        Instant startsAt = startedAt.plus(Duration.ofSeconds(Math.round(game.path("startsInMinutes").asDouble() * 60)));
        String providerState = text(game, "providerState", switch (status) {
            case SCHEDULED -> "scheduled";
            case LIVE -> "in_progress";
            case FINAL -> "final";
            case POSTPONED -> "postponed";
        });
        return new FeedGame(league, providerId, status, startsAt,
                game.hasNonNull("period") ? game.path("period").asInt() : null, text(game, "clock", null),
                team(game.path("home")), team(game.path("away")), providerState);
    }

    private static FeedGame.Team team(JsonNode team) {
        List<Integer> periods = new ArrayList<>();
        team.path("periods").forEach(points -> periods.add(points.asInt()));
        return new FeedGame.Team(text(team, "abbreviation", null), text(team, "name", ""), text(team, "city", ""),
                team.hasNonNull("score") ? team.path("score").asInt() : null, periods);
    }

    // ---------------------------------------------------------------- Chunks

    static List<String> chunkNames(Path root) throws IOException {
        Path dir = root.resolve("chunks");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> Files.isRegularFile(file.resolve("chunk.json")))
                    .map(file -> file.getFileName().toString()).filter(name -> NAME.matcher(name).matches())
                    .sorted().toList();
        }
    }

    static Info chunkInfo(ObjectMapper json, Path root, String name) throws IOException {
        JsonNode file = json.readTree(root.resolve("chunks").resolve(name).resolve("chunk.json").toFile());
        return new Info(name, text(file, "title", name), text(file, "description", ""), league(file));
    }

    static Duration chunkLength(ObjectMapper json, Path root, String name) throws IOException {
        JsonNode file = json.readTree(root.resolve("chunks").resolve(name).resolve("chunk.json").toFile());
        return Duration.ofSeconds(file.path("durationSeconds").asLong());
    }

    /**
     * A chunk's state at `position` into it: the latest recorded games response, and the latest stats response per
     * game, at or before that point, parsed by the live feed's mapping. Games keep their recorded clock and scores;
     * start times shift so the chunk's first moment is when the run started.
     */
    static State chunk(ObjectMapper json, Path root, String name, Instant startedAt, Duration position)
            throws IOException {
        Path dir = root.resolve("chunks").resolve(name);
        JsonNode file = json.readTree(dir.resolve("chunk.json").toFile());
        League league = league(file);
        Instant recordedFrom = Instant.parse(text(file, "recordedFrom", null));
        Duration shift = Duration.between(recordedFrom, startedAt);
        Path latestGames = null;
        Path firstGames = null;
        Map<String, Path> latestStats = new LinkedHashMap<>();
        long latestGamesAt = -1;
        Map<String, Long> latestStatsAt = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir.resolve("responses"))) {
            for (Path response : files.sorted().toList()) {
                Matcher match = RESPONSE.matcher(response.getFileName().toString());
                if (!match.matches()) continue;
                long at = Long.parseLong(match.group(1));
                if (firstGames == null && match.group(2).equals("games")) firstGames = response;
                if (at > position.toMillis()) continue;
                if (match.group(2).equals("games") && at >= latestGamesAt) {
                    latestGames = response;
                    latestGamesAt = at;
                } else if (match.group(2).equals("stats") && match.group(3) != null
                        && at >= latestStatsAt.getOrDefault(match.group(3), -1L)) {
                    latestStats.put(match.group(3), response);
                    latestStatsAt.put(match.group(3), at);
                }
            }
        }
        // Before the first recorded response, show it anyway, so a chunk has games from the moment it starts.
        if (latestGames == null) latestGames = firstGames;
        String prefix = "DEV-" + name + "-";
        List<FeedGame> games = new ArrayList<>();
        if (latestGames != null) {
            for (FeedGame game : ProviderParsing.games(league, body(json, latestGames))) {
                games.add(new FeedGame(league, prefix + game.providerId(), game.status(), game.startsAt().plus(shift),
                        game.period(), game.clock(), game.home(), game.away(), game.providerState()));
            }
        }
        Map<String, List<StatLine>> lines = new LinkedHashMap<>();
        for (Map.Entry<String, Path> stats : latestStats.entrySet()) {
            String game = prefix + stats.getKey();
            if (games.stream().noneMatch(feed -> feed.providerId().equals(game))) continue;
            List<StatLine> gameLines = new ArrayList<>();
            for (StatLine line : ProviderParsing.stats(league, body(json, stats.getValue()))) {
                gameLines.add(new StatLine(league, game, line.player(), line.played(), line.stats()));
            }
            lines.put(game, gameLines);
        }
        return new State(league, List.copyOf(games), lines);
    }

    /** A saved response's body: recordings wrap it as `{receivedAt, league, endpoint, query, status, body}`. */
    private static JsonNode body(ObjectMapper json, Path file) throws IOException {
        JsonNode node = json.readTree(file.toFile());
        return node.has("body") ? node.path("body") : node;
    }

    // ---------------------------------------------------------------- Helpers

    private static League league(JsonNode file) {
        String league = text(file, "league", null);
        if (league == null) throw new IllegalArgumentException("\"league\" is required (NBA or NFL)");
        return League.valueOf(league);
    }

    private static Map<String, ObjectNode> byId(JsonNode array, String what) {
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        merge(byId, array, what);
        return byId;
    }

    /** Merges objects into `into` by their `id`; nested objects merge too, other values replace. */
    private static void merge(Map<String, ObjectNode> into, JsonNode array, String what) {
        if (array.isMissingNode() || array.isNull()) return;
        if (!array.isArray()) throw new IllegalArgumentException("\"" + what + "\" must be a list");
        for (JsonNode item : (ArrayNode) array) {
            String id = item.path("id").asText("");
            if (!NAME.matcher(id).matches()) {
                throw new IllegalArgumentException("Each of " + what + " needs an \"id\" of lowercase letters, digits,"
                        + " - or _");
            }
            ObjectNode existing = into.get(id);
            into.put(id, existing == null ? ((ObjectNode) item).deepCopy() : deepMerge(existing, (ObjectNode) item));
        }
    }

    private static ObjectNode deepMerge(ObjectNode base, ObjectNode change) {
        change.properties().forEach(entry -> {
            JsonNode current = base.get(entry.getKey());
            if (current instanceof ObjectNode object && entry.getValue() instanceof ObjectNode update) {
                deepMerge(object, update);
            } else {
                base.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return base;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }
}
