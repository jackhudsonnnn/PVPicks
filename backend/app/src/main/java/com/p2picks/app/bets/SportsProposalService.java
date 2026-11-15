package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.GameStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposals for the score modes, Total Disaster (over/under the combined score) and Give And Take (the home team's
 * spread). The server builds the question and choices from the stored game, so clients can't mislabel teams. A bet
 * can be proposed until its game is Final, canceled or postponed; picks lock at the window's end or the game's start,
 * whichever is first (domain rules, "Sports bet rules").
 */
@Service
public class SportsProposalService {
    static final Set<String> MODES = Set.of("total_disaster", "give_and_take");
    private static final String OPERATION = "bet.sports.propose.v1";
    private static final BigDecimal HALF = new BigDecimal("0.5");

    public record Proposal(String mode, String gameId, BigDecimal line, boolean includesOvertime, long wagerCents,
                    int durationSec, boolean forFun) {
        public Proposal {
            if (!MODES.contains(mode)) throw new IllegalArgumentException("Unsupported sports mode");
            if (gameId == null || !gameId.matches("^(NBA|NFL)-[A-Za-z0-9_-]+$")) {
                throw new IllegalArgumentException("Invalid game");
            }
            Objects.requireNonNull(line, "line");
            // Half-point steps; whole numbers can push (and wash).
            if (line.remainder(HALF).signum() != 0) throw new IllegalArgumentException("Line must be in half points");
            if (mode.equals("total_disaster") && (line.signum() <= 0 || line.compareTo(new BigDecimal("999.5")) > 0)) {
                throw new IllegalArgumentException("Total line out of range");
            }
            if (mode.equals("give_and_take") && line.abs().compareTo(new BigDecimal("99.5")) > 0) {
                throw new IllegalArgumentException("Spread out of range");
            }
            line = line.stripTrailingZeros();
        }
    }

    private final BetProposalWriter writer;
    private final GameLookup games;
    private final ObjectMapper json;

    SportsProposalService(BetProposalWriter writer, GameLookup games, ObjectMapper json) {
        this.writer = writer;
        this.games = games;
        this.json = json;
    }

    @Transactional
    public BetProposalWriter.Result create(Jwt jwt, UUID tableId, Proposal proposal, String key) {
        ObjectNode fingerprint = json.createObjectNode();
        fingerprint.put("tableId", tableId.toString());
        fingerprint.put("mode", proposal.mode());
        fingerprint.put("gameId", proposal.gameId());
        fingerprint.put("line", proposal.line().toPlainString());
        fingerprint.put("includesOvertime", proposal.includesOvertime());
        fingerprint.put("wagerCents", proposal.wagerCents());
        fingerprint.put("durationSec", proposal.durationSec());
        fingerprint.put("forFun", proposal.forFun());
        return writer.create(jwt, tableId, key, OPERATION, write(fingerprint), false, () -> {
            GameLookup.Game game = games.find(proposal.gameId())
                    .orElseThrow(() -> new BetProposalWriter.ModeUnavailable("Game not found"));
            if (game.status() != GameStatus.SCHEDULED && game.status() != GameStatus.LIVE) {
                throw new BetProposalWriter.ModeUnavailable("The game is over");
            }
            Terms terms = terms(proposal.mode(), proposal.line(), game.home().abbreviation(),
                    game.away().abbreviation());
            ObjectNode config = json.createObjectNode();
            config.put("line", proposal.line());
            return new BetProposalWriter.NewBet(proposal.mode(), game.id(), write(config), terms.question(),
                    write(json.valueToTree(terms.choices())), proposal.wagerCents(), proposal.forFun(),
                    proposal.durationSec(), null, proposal.includesOvertime(), game.startsAt());
        });
    }

    record Terms(String question, List<String> choices) {}

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
