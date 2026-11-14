package com.p2picks.app.sports;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One game in the canonical feed schema (ADR 0004). Every provider maps into this shape; nothing downstream sees
 * provider fields. Scores are null before a game starts. {@code providerState} keeps the provider's own lifecycle
 * state (for example {@code suspended} versus {@code canceled}), which {@link GameStatus} merges but resolution must
 * tell apart (ADR 0005); null when the provider gave none.
 */
public record FeedGame(League league, String providerId, GameStatus status, Instant startsAt, Integer period,
                       String clock, Team home, Team away, String providerState) {
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

    public FeedGame(League league, String providerId, GameStatus status, Instant startsAt, Integer period,
                    String clock, Team home, Team away) {
        this(league, providerId, status, startsAt, period, clock, home, away, null);
    }

    /** Stable ID across providers' numbering, e.g. {@code NBA-18447}. */
    public String id() {
        return league + "-" + providerId;
    }

    /**
     * A team's side of the game. {@code periods} holds points per period in order (four regulation periods, then
     * overtimes), as far as the provider has reported them.
     */
    public record Team(String abbreviation, String name, String city, Integer score, List<Integer> periods) {
        public Team {
            if (abbreviation == null || abbreviation.isBlank()) throw new IllegalArgumentException("Missing team");
            name = name == null ? "" : name;
            city = city == null ? "" : city;
            if (score != null && score < 0) throw new IllegalArgumentException("Negative score");
            periods = periods == null ? List.of() : List.copyOf(periods);
            if (periods.stream().anyMatch(points -> points < 0)) throw new IllegalArgumentException("Negative period");
        }

        public Team(String abbreviation, String name, String city, Integer score) {
            this(abbreviation, name, city, score, List.of());
        }

        /** Points at the end of regulation (the first four periods), or null until all four are known. */
        public Integer regulationScore() {
            return periods.size() < 4 ? null : periods.get(0) + periods.get(1) + periods.get(2) + periods.get(3);
        }
    }
}
