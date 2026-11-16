package com.p2picks.app.bets;

import com.p2picks.app.sports.GameLookup;
import com.p2picks.app.sports.PlayerStatsLookup;
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
public class SportsResolutionService {
    public enum Outcome { RESOLVED, WAITING, SKIPPED }

    private final JdbcTemplate jdbc;
    private final SportsBetLoader loader;
    private final GameLookup games;
    private final BetResolutionWriter writer;
    private final PlayerStatsLookup stats;

    SportsResolutionService(JdbcTemplate jdbc, SportsBetLoader loader, GameLookup games, BetResolutionWriter writer,
            PlayerStatsLookup stats) {
        this.jdbc = jdbc;
        this.loader = loader;
        this.games = games;
        this.writer = writer;
        this.stats = stats;
    }

    /** The automatic result for a bet's terms: score modes, player modes or Scorcerer. */
    private SportsOutcome.Result decide(SportsOutcome.Terms terms, String gameId, Instant now) {
        GameLookup.Game game = games.find(gameId).orElse(null);
        Instant suspendedSince = game == null ? null : games.suspendedSince(game.id());
        if (SportsProposalService.PLAYER_MODES.contains(terms.mode())) {
            return PlayerOutcome.decide(terms, game, suspendedSince, now, facts(gameId));
        }
        if (terms.mode().equals(SportsProposalService.SCORCERER)) {
            return ScorcererOutcome.decide(terms, game, suspendedSince, now, stats.scores(gameId));
        }
        return SportsOutcome.decide(terms, game, suspendedSince, now);
    }

    private PlayerOutcome.Facts facts(String gameId) {
        java.util.Map<String, PlayerStatsLookup.GamePlayer> lines = new java.util.HashMap<>();
        for (PlayerStatsLookup.GamePlayer player : stats.players(gameId)) lines.put(player.id(), player);
        return new PlayerOutcome.Facts() {
            @Override
            public boolean played(String playerId) {
                PlayerStatsLookup.GamePlayer line = lines.get(playerId);
                return line != null && line.played();
            }

            @Override
            public int value(String playerId, String stat) {
                PlayerStatsLookup.GamePlayer line = lines.get(playerId);
                return line == null ? 0 : line.stat(stat);
            }

            @Override
            public Instant overtimeStart() {
                return stats.overtimeStart(gameId);
            }

            @Override
            public int valueBefore(String playerId, String stat, Instant cutoff) {
                return stats.valueBefore(gameId, playerId, stat, cutoff);
            }

            @Override
            public Instant firstReached(String playerId, String stat, int target) {
                return stats.firstReached(gameId, playerId, stat, target);
            }
        };
    }

    @Transactional
    public Outcome resolve(UUID betId, Instant now) {
        // Decide from unlocked data first, so waiting bets never lock anyone; the locked pass decides again.
        SportsBetLoader.Peek peek = loader.peek(betId);
        if (peek == null || peek.gameId() == null || !peek.status().equals("PENDING")) return Outcome.SKIPPED;
        if (decide(peek.terms(), peek.gameId(), now) instanceof SportsOutcome.Result.Wait) {
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
        SportsOutcome.Result result = decide(bet.terms(), bet.gameId(), now);
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
