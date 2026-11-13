package com.p2picks.app.sports;

import java.time.Instant;
import java.util.Objects;

/**
 * One game in the canonical feed schema (ADR 0004). Every provider maps into this shape; nothing downstream sees
 * provider fields. Scores are null before a game starts.
 */
public record FeedGame(League league, String providerId, GameStatus status, Instant startsAt, Integer period,
                       String clock, Team home, Team away) {
    public FeedGame {
        Objects.requireNonNull(league, "league");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(away, "away");
        if (providerId == null || !providerId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid provider game ID");
        }
        if (period != null && period < 1) throw new IllegalArgumentException("Period must be at least 1");
    }

    /** Stable ID across providers' numbering, e.g. {@code NBA-18447}. */
    public String id() {
        return league + "-" + providerId;
    }

    public record Team(String abbreviation, String name, String city, Integer score) {
        public Team {
            if (abbreviation == null || abbreviation.isBlank()) throw new IllegalArgumentException("Missing team");
            name = name == null ? "" : name;
            city = city == null ? "" : city;
            if (score != null && score < 0) throw new IllegalArgumentException("Negative score");
        }
    }
}
