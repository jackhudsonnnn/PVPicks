package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import com.p2picks.app.sports.League;
import com.p2picks.app.sports.PlayerStatsLookup;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposals for the sports modes. Score modes: Total Disaster (over/under the combined score) and Give And Take
 * (the home team's spread). Player modes, both players from the bet's game: Either Or (who gets more of a stat),
 * Prop Hunt (one player over/under a line) and King Of The Hill (first to a target, or Neither). Scorcerer (NFL):
 * how the next score is made. The server builds the question and choices from the stored game and players, so
 * clients can't mislabel them. A bet can be proposed until its game is Final, canceled or postponed; picks lock at
 * the window's end or the game's start, whichever is first (domain rules, "Sports bet rules").
 */
@Service
public class SportsProposalService {
    static final Set<String> SCORE_MODES = Set.of("total_disaster", "give_and_take");
    static final Set<String> PLAYER_MODES = Set.of("either_or", "prop_hunt", "king_of_the_hill");
    static final String SCORCERER = "scorcerer";
    static final Set<String> MODES = Set.of("total_disaster", "give_and_take", "either_or", "prop_hunt",
            "king_of_the_hill", SCORCERER);
    /** Safeties and other 2-point plays don't count (owner decision, 2026-11-15). */
    static final List<String> SCORCERER_CHOICES = List.of("Touchdown", "Field goal", "No more TDs or FGs");
    private static final String OPERATION = "bet.sports.propose.v1";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /**
     * A sports proposal. {@code line} is the total or spread (score modes), the stat line (Prop Hunt) or the target
     * (King Of The Hill), and is null otherwise. {@code stat} and {@code players} are for the player modes.
     */
    public record Proposal(String mode, String gameId, BigDecimal line, boolean includesOvertime, long wagerCents,
                           int durationSec, boolean forFun, String stat, List<String> players) {
        public Proposal {
            if (!MODES.contains(mode)) throw new IllegalArgumentException("Unsupported sports mode");
            if (gameId == null || !gameId.matches("^(NBA|NFL)-[A-Za-z0-9_-]+$")) {
                throw new IllegalArgumentException("Invalid game");
            }
            players = players == null ? List.of() : List.copyOf(players);
            int wantedPlayers = switch (mode) {
                case "prop_hunt" -> 1;
                case "either_or", "king_of_the_hill" -> 2;
                default -> 0;
            };
            if (players.size() != wantedPlayers || players.stream().distinct().count() != players.size()) {
                throw new IllegalArgumentException("Wrong players for the mode");
            }
            if (PLAYER_MODES.contains(mode) == (stat == null)) throw new IllegalArgumentException("Stat mismatch");
            boolean needsLine = !mode.equals("either_or") && !mode.equals(SCORCERER);
            if (needsLine != (line != null)) throw new IllegalArgumentException("Line mismatch");
            if (line != null) {
                // Half-point steps; whole numbers can push (and wash). Targets are whole numbers.
                if (line.remainder(HALF).signum() != 0) throw new IllegalArgumentException("Line must be in half points");
                if (mode.equals("total_disaster") && (line.signum() <= 0 || line.compareTo(new BigDecimal("999.5")) > 0)) {
                    throw new IllegalArgumentException("Total line out of range");
                }
                if (mode.equals("give_and_take") && line.abs().compareTo(new BigDecimal("99.5")) > 0) {
                    throw new IllegalArgumentException("Spread out of range");
                }
                if (mode.equals("prop_hunt") && (line.signum() <= 0 || line.compareTo(new BigDecimal("999.5")) > 0)) {
                    throw new IllegalArgumentException("Prop line out of range");
                }
                if (mode.equals("king_of_the_hill") && (line.stripTrailingZeros().scale() > 0 || line.signum() <= 0
                        || line.compareTo(new BigDecimal("999")) > 0)) {
                    throw new IllegalArgumentException("Target must be a whole number");
                }
                line = line.stripTrailingZeros();
            }
        }

        /** A score-mode proposal. */
        public Proposal(String mode, String gameId, BigDecimal line, boolean includesOvertime, long wagerCents,
                int durationSec, boolean forFun) {
            this(mode, gameId, line, includesOvertime, wagerCents, durationSec, forFun, null, List.of());
        }
    }

    /** The proposal can't be made on this game as it stands: a player or line the rules don't allow. */
    static class InvalidTerms extends RuntimeException {
        InvalidTerms(String message) {
            super(message);
        }
    }

    private final BetProposalWriter writer;
    private final GameLookup games;
    private final PlayerStatsLookup players;
    private final ObjectMapper json;

    SportsProposalService(BetProposalWriter writer, GameLookup games, PlayerStatsLookup players, ObjectMapper json) {
        this.writer = writer;
        this.games = games;
        this.players = players;
        this.json = json;
    }

    @Transactional
    public BetProposalWriter.Result create(Jwt jwt, UUID tableId, Proposal proposal, String key) {
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.put("tableId", tableId.toString());
        fingerprint.put("mode", proposal.mode());
        fingerprint.put("gameId", proposal.gameId());
        fingerprint.put("line", proposal.line() == null ? null : proposal.line().toPlainString());
        fingerprint.put("includesOvertime", proposal.includesOvertime());
        fingerprint.put("wagerCents", proposal.wagerCents());
        fingerprint.put("durationSec", proposal.durationSec());
        fingerprint.put("forFun", proposal.forFun());
        fingerprint.put("stat", proposal.stat());
        fingerprint.set("players", json.valueToTree(proposal.players()));
        return writer.create(jwt, tableId, key, OPERATION, write(fingerprint), false, () -> {
            GameLookup.Game game = games.find(proposal.gameId())
                    .orElseThrow(() -> new BetProposalWriter.ModeUnavailable("Game not found"));
            if (game.status() != GameStatus.SCHEDULED && game.status() != GameStatus.LIVE) {
                throw new BetProposalWriter.ModeUnavailable("The game is over");
            }
            Terms terms = terms(proposal, game);
            ObjectNode config = json.createObjectNode();
            if (proposal.line() != null) config.put("line", proposal.line());
            if (proposal.stat() != null) config.put("stat", proposal.stat());
            if (!proposal.players().isEmpty()) config.set("players", json.valueToTree(proposal.players()));
            boolean overtime = !proposal.mode().equals(SCORCERER) && proposal.includesOvertime();
            return new BetProposalWriter.NewBet(proposal.mode(), game.id(), write(config), terms.question(),
                    write(json.valueToTree(terms.choices())), proposal.wagerCents(), proposal.forFun(),
                    proposal.durationSec(), null, proposal.mode().equals(SCORCERER) ? null : overtime,
                    game.startsAt());
        });
    }

    record Terms(String question, List<String> choices) {}

    private Terms terms(Proposal proposal, GameLookup.Game game) {
        String home = game.home().abbreviation();
        String away = game.away().abbreviation();
        if (proposal.mode().equals("total_disaster") && BigDecimal.valueOf(
                SportsOutcome.combinedScore(game, proposal.includesOvertime())).compareTo(proposal.line()) >= 0) {
            // Like Prop Hunt: a line the score already passed would make Over a sure thing (domain rules).
            throw new InvalidTerms("The line must be above the current combined score");
        }
        if (SCORE_MODES.contains(proposal.mode())) return terms(proposal.mode(), proposal.line(), home, away);
        if (proposal.mode().equals(SCORCERER)) {
            if (game.league() != League.NFL) throw new InvalidTerms("Scorcerer is NFL only");
            return new Terms(away + " @ " + home + ": next touchdown or field goal?", SCORCERER_CHOICES);
        }
        if (!PlayerStatsLookup.isStat(game.league(), proposal.stat())) throw new InvalidTerms("Unknown stat");
        List<PlayerStatsLookup.GamePlayer> picked = new ArrayList<>();
        for (String id : proposal.players()) {
            picked.add(players.player(game.id(), id).orElseThrow(() -> new InvalidTerms("Player isn't in this game")));
        }
        String stat = PlayerStatsLookup.label(game.league(), proposal.stat()).toLowerCase(java.util.Locale.ROOT);
        List<String> names = names(picked);
        return switch (proposal.mode()) {
            case "either_or" -> new Terms("Who gets more " + stat + ": " + names.get(0) + " or " + names.get(1) + "?",
                    names);
            case "prop_hunt" -> {
                PlayerStatsLookup.GamePlayer player = picked.getFirst();
                // The line must start above the player's current number (domain rules, "Wash triggers").
                if (BigDecimal.valueOf(player.stat(proposal.stat())).compareTo(proposal.line()) >= 0) {
                    throw new InvalidTerms("The line must be above the player's current number");
                }
                String line = proposal.line().toPlainString();
                yield new Terms(names.getFirst() + " " + stat + ": over or under " + line + "?",
                        List.of("Over " + line, "Under " + line));
            }
            case "king_of_the_hill" -> {
                int target = proposal.line().intValueExact();
                if (picked.stream().anyMatch(player -> player.stat(proposal.stat()) >= target)) {
                    throw new InvalidTerms("The target must be above both players' current numbers");
                }
                yield new Terms("First to " + target + " " + stat + ": " + names.get(0) + " or " + names.get(1) + "?",
                        List.of(names.get(0), names.get(1), "Neither"));
            }
            default -> throw new IllegalStateException("Not a player mode");
        };
    }

    /** Display names as choices; two players with the same name are told apart by team. */
    private static List<String> names(List<PlayerStatsLookup.GamePlayer> players) {
        List<String> names = players.stream().map(PlayerStatsLookup.GamePlayer::name).toList();
        if (names.size() == 2 && names.get(0).equalsIgnoreCase(names.get(1))) {
            return players.stream().map(player -> player.name() + " (" + player.team() + ")").toList();
        }
        return names;
    }

    /** The question and choices for a score mode; the first choice is "over" or the home side. */
    static Terms terms(String mode, BigDecimal line, String home, String away) {
        String value = line.stripTrailingZeros().toPlainString();
        return switch (mode) {
            case "total_disaster" -> new Terms(away + " + " + home + " combined: over or under " + value + "?",
                    List.of("Over " + value, "Under " + value));
            case "give_and_take" -> new Terms(home + " " + signed(line) + ", who covers?",
                    List.of(home + " " + signed(line), away + " " + signed(line.negate())));
            default -> throw new IllegalArgumentException("Unsupported sports mode");
        };
    }

    private static String signed(BigDecimal value) {
        String plain = value.stripTrailingZeros().toPlainString();
        return value.signum() > 0 ? "+" + plain : plain;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize bet proposal", exception);
        }
    }
}
