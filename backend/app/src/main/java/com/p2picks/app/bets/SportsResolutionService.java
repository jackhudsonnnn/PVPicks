package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the feed's automatic result to one Pending sports bet (domain rules, "Sports outcomes"). */
@Service
class SportsResolutionService {
    enum Outcome { RESOLVED, WAITING, SKIPPED }

    private final JdbcTemplate jdbc;
    private final SportsBetLoader loader;
    private final GameLookup games;
    private final BetResolutionWriter writer;

    SportsResolutionService(JdbcTemplate jdbc, SportsBetLoader loader, GameLookup games, BetResolutionWriter writer) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.games = games;
        this.writer = writer;
    }

    @Transactional
    public Outcome resolve(UUID betId, Instant now) {
        // Decide from unlocked data first, so waiting bets never lock anyone; the locked pass decides again.
        SportsBetLoader.Peek peek = loader.peek(betId);
        if (peek == null || peek.gameId() == null || !peek.status().equals("PENDING")) return Outcome.SKIPPED;
        GameLookup.Game current = games.find(peek.gameId()).orElse(null);
        if (SportsOutcome.decide(peek.terms(), current, current == null ? null : games.suspendedSince(current.id()),
                now) instanceof SportsOutcome.Result.Wait) {
            return Outcome.WAITING;
        }
        List<UUID> affected = loader.affectedUsers(betId);
        if (affected == null) return Outcome.SKIPPED;
        String ids = "{" + String.join(",", affected.stream().map(UUID::toString).toList()) + "}";
        Set<UUID> locked = new HashSet<>(jdbc.query("SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id "
                + "FOR UPDATE", (rs, row) -> rs.getObject("id", UUID.class), ids));
        SportsBetLoader.Loaded bet = loader.load(betId, locked);
        if (bet == null || !bet.status().equals("PENDING") || bet.gameId() == null
                || !SportsProposalService.MODES.contains(bet.mode())) {
            return Outcome.SKIPPED;
        }
        GameLookup.Game game = games.find(bet.gameId()).orElse(null);
        SportsOutcome.Result result = SportsOutcome.decide(bet.terms(), game,
                game == null ? null : games.suspendedSince(game.id()), now);
        if (result instanceof SportsOutcome.Result.Winner winner) {
            writer.apply(bet.bet(), winner.choice(), null, null, false);
        } else if (result instanceof SportsOutcome.Result.Wash wash) {
            writer.apply(bet.bet(), null, wash.reason(), null, false);
        } else {
            return Outcome.WAITING;
        }
        return Outcome.RESOLVED;
    }
}
