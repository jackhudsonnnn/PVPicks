package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.PlayerStatsLookup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Live stats for a sports bet's ticket Details: the numbers its mode follows (a combined score, a margin, a player's
 * stat, each team's touchdowns and field goals) when the bet was proposed, when picks locked, and now.
 *
 * <p>The first two are snapshots in {@code bets.baseline} ({"proposed": {...}, "locked": {...}}), written by the
 * proposal and lock transactions. Once the bet is resolved or washed, "now" stops: it's the {@code result} snapshot
 * (with the score and clock in {@code resultGame}), written when the outcome is applied, and the read is
 * {@code closed}.
 */
@Component
public class BetLiveStats {
    /** One tracked number. {@code target} is the line or target it's measured against, when the mode has one. */
    public record Row(String label, String detail, Integer proposed, Integer locked, Integer current,
            BigDecimal target, String targetLabel) {}

    public record Team(String abbreviation, Integer score) {}

    /** {@code closed}: the bet is resolved or washed, so the numbers (and the score and clock) are as of then. */
    public record Live(String gameId, String league, String status, Integer period, String clock, Team away,
            Team home, List<Row> rows, boolean closed) {}

    private record Bet(String mode, String gameId, JsonNode config, boolean includesOvertime, JsonNode baseline,
            String status) {
        boolean closed() {
            return status.equals("SETTLED") || status.equals("WASHED");
        }
    }

    private final JdbcTemplate jdbc;
    private final GameLookup games;
    private final PlayerStatsLookup stats;
    private final ObjectMapper json;

    BetLiveStats(JdbcTemplate jdbc, GameLookup games, PlayerStatsLookup stats, ObjectMapper json) {
        this.jdbc = jdbc;
        this.games = games;
        this.stats = stats;
        this.json = json;
    }

    /** Records the bet's tracked numbers as of now under {@code phase} ("proposed" or "locked"). No-op for U2Pick. */
    public void capture(UUID betId, String phase) {
        Optional<Bet> bet = bet(betId);
        if (bet.isEmpty() || bet.get().gameId() == null) return;
        Map<String, Integer> values = values(bet.get());
        if (values.isEmpty()) return;
        try {
            jdbc.update("""
                    UPDATE bets SET baseline = COALESCE(baseline, '{}'::jsonb) || jsonb_build_object(?::text, ?::jsonb)
                    WHERE id = ?
                    """, phase, json.writeValueAsString(values), betId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot write live stats", exception);
        }
    }

    /**
     * Freezes the tracked numbers, the score and the clock as the bet is called (resolved or washed; a correction
     * records them again). No-op for a bet without a game.
     */
    public void captureResult(UUID betId) {
        Optional<Bet> bet = bet(betId);
        if (bet.isEmpty() || bet.get().gameId() == null) return;
        Optional<GameLookup.Game> found = games.find(bet.get().gameId());
        if (found.isEmpty()) return;
        GameLookup.Game g = found.get();
        Map<String, Object> clock = jdbc.queryForMap("SELECT period, clock FROM games WHERE id = ?", g.id());
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("status", g.status().name());
        game.put("period", clock.get("period"));
        game.put("clock", clock.get("clock"));
        game.put("awayScore", g.away().score());
        game.put("homeScore", g.home().score());
        try {
            jdbc.update("""
                    UPDATE bets SET baseline = COALESCE(baseline, '{}'::jsonb)
                        || jsonb_build_object('result', ?::jsonb, 'resultGame', ?::jsonb)
                    WHERE id = ?
                    """, json.writeValueAsString(values(bet.get())), json.writeValueAsString(game), betId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot write live stats", exception);
        }
    }

    /** The bet's live stats, or empty for a bet without a game (U2Pick) or whose game is gone. */
    public Optional<Live> read(UUID betId) {
        Optional<Bet> found = bet(betId);
        if (found.isEmpty() || found.get().gameId() == null) return Optional.empty();
        Bet bet = found.get();
        Optional<GameLookup.Game> game = games.find(bet.gameId());
        if (game.isEmpty()) return Optional.empty();
        GameLookup.Game g = game.get();
        JsonNode proposed = bet.baseline() == null ? null : bet.baseline().get("proposed");
        JsonNode locked = bet.baseline() == null ? null : bet.baseline().get("locked");
        JsonNode result = bet.closed() && bet.baseline() != null ? bet.baseline().get("result") : null;
        JsonNode at = bet.closed() && bet.baseline() != null ? bet.baseline().get("resultGame") : null;
        Map<String, Integer> live = result == null ? values(bet) : Map.of();
        List<Row> rows = new ArrayList<>();
        for (Tracked tracked : tracked(bet, g)) {
            Integer now = result == null ? live.get(tracked.key()) : number(result, tracked.key());
            rows.add(new Row(tracked.label(), tracked.detail(), number(proposed, tracked.key()),
                    number(locked, tracked.key()), now, tracked.target(), tracked.targetLabel()));
        }
        Team away = new Team(g.away().abbreviation(), g.away().score());
        Team home = new Team(g.home().abbreviation(), g.home().score());
        if (at != null) {
            return Optional.of(new Live(g.id(), g.league().name(), at.path("status").asText(),
                    number(at, "period"), at.hasNonNull("clock") ? at.get("clock").asText() : null,
                    new Team(away.abbreviation(), number(at, "awayScore")),
                    new Team(home.abbreviation(), number(at, "homeScore")), List.copyOf(rows), true));
        }
        Map<String, Object> clock = jdbc.queryForMap("SELECT period, clock FROM games WHERE id = ?", bet.gameId());
        return Optional.of(new Live(g.id(), g.league().name(), g.status().name(), (Integer) clock.get("period"),
                (String) clock.get("clock"), away, home, List.copyOf(rows), bet.closed()));
    }

    private record Tracked(String key, String label, String detail, BigDecimal target, String targetLabel) {}

    /** What each mode follows, in display order. */
    private List<Tracked> tracked(Bet bet, GameLookup.Game game) {
        String home = game.home().abbreviation();
        String away = game.away().abbreviation();
        BigDecimal line = bet.config().hasNonNull("line") ? bet.config().path("line").decimalValue() : null;
        return switch (bet.mode()) {
            case "total_disaster" -> List.of(new Tracked("combined", "Combined score",
                    away + " + " + home + (bet.includesOvertime() ? "" : ", regulation only"), line, "Line"));
            case "give_and_take" -> List.of(new Tracked("margin", home + " margin",
                    home + " minus " + away, line, "Spread"));
            case "scorcerer" -> {
                String big = game.league() == com.p2picks.app.sports.League.NFL ? "touchdowns" : "3-pointers";
                String small = game.league() == com.p2picks.app.sports.League.NFL ? "field goals" : "2-pointers";
                yield List.of(
                        new Tracked("away_big", away + " " + big, null, null, null),
                        new Tracked("away_small", away + " " + small, null, null, null),
                        new Tracked("home_big", home + " " + big, null, null, null),
                        new Tracked("home_small", home + " " + small, null, null, null));
            }
            case "either_or", "prop_hunt", "king_of_the_hill" -> {
                String stat = bet.config().path("stat").asText();
                String statLabel = PlayerStatsLookup.label(game.league(), stat);
                List<Tracked> rows = new ArrayList<>();
                for (JsonNode id : bet.config().path("players")) {
                    var player = stats.player(bet.gameId(), id.asText());
                    String name = player.map(PlayerStatsLookup.GamePlayer::name).orElse("A player");
                    String detail = player.map(p -> String.join(" · ", nonBlank(p.position(), p.team())))
                            .orElse("");
                    rows.add(new Tracked("player:" + id.asText(), name + " " + statLabel.toLowerCase(), detail,
                            bet.mode().equals("either_or") ? null : line,
                            bet.mode().equals("prop_hunt") ? "Line" : "Target"));
                }
                yield List.copyOf(rows);
            }
            default -> List.of();
        };
    }

    /** The mode's tracked numbers right now. Scores before the start count as 0. */
    private Map<String, Integer> values(Bet bet) {
        Map<String, Integer> values = new LinkedHashMap<>();
        Optional<GameLookup.Game> found = games.find(bet.gameId());
        if (found.isEmpty()) return values;
        GameLookup.Game game = found.get();
        int home = game.home().score() == null ? 0 : game.home().score();
        int away = game.away().score() == null ? 0 : game.away().score();
        switch (bet.mode()) {
            case "total_disaster" -> values.put("combined", game.home().score() == null ? 0
                    : SportsOutcome.combinedScore(game, bet.includesOvertime()));
            case "give_and_take" -> values.put("margin", home - away);
            case "scorcerer" -> {
                var totals = stats.scoringTotals(bet.gameId(), game.league(), game.home().abbreviation(),
                        game.away().abbreviation());
                var last = totals.isEmpty() ? null : totals.getLast();
                values.put("away_big", last == null ? 0 : last.away().big());
                values.put("away_small", last == null ? 0 : last.away().small());
                values.put("home_big", last == null ? 0 : last.home().big());
                values.put("home_small", last == null ? 0 : last.home().small());
            }
            case "either_or", "prop_hunt", "king_of_the_hill" -> {
                String stat = bet.config().path("stat").asText();
                for (JsonNode id : bet.config().path("players")) {
                    values.put("player:" + id.asText(),
                            stats.player(bet.gameId(), id.asText()).map(p -> p.stat(stat)).orElse(0));
                }
            }
            default -> { }
        }
        return values;
    }

    private Optional<Bet> bet(UUID betId) {
        return jdbc.query("""
                SELECT mode, game_id, config::text AS config, includes_overtime, baseline::text AS baseline, status
                FROM bets WHERE id = ?
                """, (rs, row) -> new Bet(rs.getString("mode"), rs.getString("game_id"), tree(rs.getString("config")),
                        !Boolean.FALSE.equals(rs.getObject("includes_overtime")),
                        rs.getString("baseline") == null ? null : tree(rs.getString("baseline")),
                        rs.getString("status")), betId)
                .stream().findFirst();
    }

    private JsonNode tree(String serialized) {
        try {
            return json.readTree(serialized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read bet", exception);
        }
    }

    private static Integer number(JsonNode values, String key) {
        return values == null || !values.hasNonNull(key) ? null : values.get(key).asInt();
    }

    private static List<String> nonBlank(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) if (part != null && !part.isBlank()) kept.add(part);
        return kept;
    }
}
