package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads saved BallDontLie response bodies with the same mapping the live feed uses, so replayed recordings go
 * through the real parsing path (dev chunks, tests).
 */
public final class ProviderParsing {
    private ProviderParsing() {}

    /** Games in a `games` response body; unreadable games are skipped, as live. */
    public static List<FeedGame> games(League league, JsonNode body) {
        List<FeedGame> games = new ArrayList<>();
        for (JsonNode game : body.path("data")) {
            FeedGame parsed = league == League.NBA ? BallDontLieFeed.nba(game) : BallDontLieFeed.nfl(game);
            if (parsed != null) games.add(parsed);
        }
        return games;
    }

    /** Player lines in a `stats` response body; unreadable rows are skipped, as live. */
    public static List<StatLine> stats(League league, JsonNode body) {
        List<StatLine> lines = new ArrayList<>();
        for (JsonNode row : body.path("data")) {
            StatLine line = league == League.NBA ? StatLine.nba(row) : StatLine.nfl(row);
            if (line != null) lines.add(line);
        }
        return lines;
    }
}
