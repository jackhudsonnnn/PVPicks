package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * BallDontLie, the runtime provider (ADR 0004; https://docs.balldontlie.io, https://nfl.balldontlie.io). Requests
 * carry the API key from {@code PVPICKS_BALLDONTLIE_API_KEY}; without a key every read fails as unavailable.
 * Provider fields are mapped into {@link FeedGame} here and nowhere else. The lifecycle comes from
 * {@code status_state}, as the provider recommends; the free-text {@code status} is only a fallback and a label.
 * A game whose start time or teams cannot be read, or whose state is {@code unknown}, is skipped.
 */
@Component
class BallDontLieFeed implements GameFeed {
    private static final Logger log = LoggerFactory.getLogger(BallDontLieFeed.class);
    private static final int MAX_PAGES = 5;
    private static final Pattern ORDINAL_PERIOD = Pattern.compile("^(\\d)(st|nd|rd|th)\\b");

    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper json;
    private final RestClient http;
    private final FeedRecorder recorder;

    BallDontLieFeed(@Value("${pvpicks.sports.balldontlie.api-key:}") String apiKey,
            @Value("${pvpicks.sports.balldontlie.base-url:https://api.balldontlie.io}") String baseUrl,
            ObjectMapper json, FeedRecorder recorder) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.json = json;
        this.recorder = recorder;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(requests).build();
    }

    boolean configured() {
        return !apiKey.isEmpty();
    }

    @Override
    public List<FeedGame> games(League league, List<LocalDate> dates) throws FeedUnavailable {
        if (!configured()) throw new FeedUnavailable("No BallDontLie API key is configured", false, null);
        List<FeedGame> games = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = fetch(league, dates, cursor);
            for (JsonNode game : body.path("data")) {
                FeedGame parsed = league == League.NBA ? nba(game) : nfl(game);
                if (parsed != null) games.add(parsed);
            }
            JsonNode next = body.path("meta").path("next_cursor");
            if (next.isMissingNode() || next.isNull()) return games;
            cursor = next.asText();
        }
        return games;
    }

    private JsonNode fetch(League league, List<LocalDate> dates, String cursor) throws FeedUnavailable {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path(league == League.NBA ? "/v1/games" : "/nfl/v1/games")
                .queryParam("per_page", 100);
        for (LocalDate date : dates) uri.queryParam("dates[]", date.toString());
        if (cursor != null) uri.queryParam("cursor", cursor);
        var target = uri.encode().build().toUri();
        try {
            String body = http.get().uri(target).header("Authorization", apiKey).retrieve().body(String.class);
            recorder.record(league, "games", target.getRawQuery(), 200, body);
            return json.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new FeedUnavailable("BallDontLie returned HTTP " + status, status == 429, exception);
        } catch (RestClientException | java.io.IOException exception) {
            throw new FeedUnavailable("BallDontLie could not be read", false, exception);
        }
    }

    /** NBA games: {@code datetime} is the start; {@code status} is "Final", a period label, or the start time. */
    static FeedGame nba(JsonNode game) {
        try {
            String status = game.path("status").asText("");
            Instant startsAt = instant(game.path("datetime").asText(null));
            if (startsAt == null) startsAt = instant(status);
            if (startsAt == null) return null;
            int period = game.path("period").asInt(0);
            GameStatus state = state(game.path("status_state").asText(null), status, period);
            if (state == null) return null;
            String time = game.path("time").asText("").trim();
            String clock = state != GameStatus.LIVE ? null
                    : !time.isEmpty() && !time.equalsIgnoreCase("final") ? time : status.trim();
            return new FeedGame(League.NBA, game.path("id").asText(), state, startsAt,
                    state == GameStatus.LIVE && period >= 1 ? period : null, emptyToNull(clock),
                    team(game.path("home_team"), score(game, "home_team_score", state)),
                    team(game.path("visitor_team"), score(game, "visitor_team_score", state)));
        } catch (RuntimeException exception) {
            log.debug("Skipped an unreadable NBA game", exception);
            return null;
        }
    }

    /**
     * NFL games: {@code date} is the start. The response has no clock or quarter field, so the period is the
     * latest quarter with a score (overtime is period 5) and the clock is the status label.
     */
    static FeedGame nfl(JsonNode game) {
        try {
            String status = game.path("status").asText("");
            Instant startsAt = instant(game.path("date").asText(null));
            if (startsAt == null) return null;
            int period = scoredPeriod(game);
            if (period == 0) period = ordinalPeriod(status);
            GameStatus state = state(game.path("status_state").asText(null), status, period);
            if (state == null) return null;
            String clock = state != GameStatus.LIVE ? null : status.trim();
            return new FeedGame(League.NFL, game.path("id").asText(), state, startsAt,
                    state == GameStatus.LIVE && period >= 1 ? period : null, emptyToNull(clock),
                    team(game.path("home_team"), score(game, "home_team_score", state)),
                    team(game.path("visitor_team"), score(game, "visitor_team_score", state)));
        } catch (RuntimeException exception) {
            log.debug("Skipped an unreadable NFL game", exception);
            return null;
        }
    }

    /**
     * The canonical status from {@code status_state}; null for {@code unknown} (the provider cannot classify the
     * game, so it is skipped). Without a state, falls back to the label.
     */
    static GameStatus state(String statusState, String status, int period) {
        if (statusState == null || statusState.isBlank()) return classify(status, period);
        return switch (statusState.trim().toLowerCase(Locale.ROOT)) {
            case "scheduled", "delayed" -> GameStatus.SCHEDULED;
            case "in_progress" -> GameStatus.LIVE;
            case "final" -> GameStatus.FINAL;
            case "postponed", "canceled", "cancelled", "suspended", "abandoned" -> GameStatus.POSTPONED;
            default -> null;
        };
    }

    /** The latest NFL period with a score, from {@code *_q1}..{@code *_q4} and {@code *_ot}. */
    static int scoredPeriod(JsonNode game) {
        if (scored(game, "ot")) return 5;
        for (int quarter = 4; quarter >= 1; quarter--) {
            if (scored(game, "q" + quarter)) return quarter;
        }
        return 0;
    }

    private static boolean scored(JsonNode game, String period) {
        return game.path("home_team_" + period).isInt() || game.path("visitor_team_" + period).isInt();
    }

    /** Maps a provider status label to the canonical status; anything not clearly live or over is scheduled. */
    static GameStatus classify(String status, int period) {
        String label = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (label.startsWith("final")) return GameStatus.FINAL;
        if (label.contains("postpon") || label.contains("cancel") || label.contains("suspend")) {
            return GameStatus.POSTPONED;
        }
        if (instant(status) != null || label.isEmpty() || label.contains("scheduled")
                || label.matches(".*\\d{1,2}:\\d{2}\\s*(am|pm).*")) {
            return GameStatus.SCHEDULED;
        }
        if (period >= 1 || label.contains("half") || label.contains("qtr") || label.contains("quarter")
                || label.matches(".*\\b\\d?ot\\b.*") || label.contains("overtime")
                || label.contains("progress")) {
            return GameStatus.LIVE;
        }
        return GameStatus.SCHEDULED;
    }

    private static int ordinalPeriod(String status) {
        Matcher matcher = ORDINAL_PERIOD.matcher(status == null ? "" : status.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static FeedGame.Team team(JsonNode team, Integer score) {
        String city = team.path("city").asText(null);
        if (city == null) city = team.path("location").asText("");
        return new FeedGame.Team(team.path("abbreviation").asText(null), team.path("name").asText(""), city, score);
    }

    private static Integer score(JsonNode game, String field, GameStatus state) {
        if (state == GameStatus.SCHEDULED || state == GameStatus.POSTPONED) return null;
        JsonNode score = game.path(field);
        return score.isInt() && score.asInt() >= 0 ? score.asInt() : null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException exception) {
            try {
                return Instant.parse(value.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
