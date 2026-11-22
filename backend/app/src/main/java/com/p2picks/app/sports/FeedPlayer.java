package com.p2picks.app.sports;

import com.fasterxml.jackson.databind.JsonNode;

/** A player in the canonical feed schema; {@code teamAbbreviation} is null for free agents. */
public record FeedPlayer(League league, String providerId, String firstName, String lastName, String position,
                         String teamAbbreviation) {
    public FeedPlayer {
        if (providerId == null || !providerId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid provider player ID");
        }
        firstName = firstName == null ? "" : firstName.trim();
        lastName = lastName == null ? "" : lastName.trim();
    }

    /** Stable ID across providers' numbering, e.g. {@code NBA-3}. */
    public String id() {
        return league + "-" + providerId;
    }

    /** Reads a provider player and their team; null when the player has no usable ID. */
    static FeedPlayer from(League league, JsonNode player, JsonNode team) {
        String id = player.path("id").asText("");
        if (!id.matches("[A-Za-z0-9_-]+")) return null;
        String position = player.path("position_abbreviation").asText(null);
        if (position == null || position.isBlank()) position = player.path("position").asText(null);
        String abbreviation = team.path("abbreviation").asText(null);
        return new FeedPlayer(league, id, player.path("first_name").asText(""), player.path("last_name").asText(""),
                position == null || position.isBlank() ? null : position,
                abbreviation == null || abbreviation.isBlank() ? null : abbreviation);
    }
}
