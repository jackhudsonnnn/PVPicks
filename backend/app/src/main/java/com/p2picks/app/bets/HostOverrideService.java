package com.p2picks.app.bets;

import com.p2picks.app.chat.ChatMessageWriter;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A host's final say on a sports bet (domain rules, "Sports outcomes and host overrides"): wash it, or set its result
 * to one of its choices, while it's Active, Pending, or resolved with points not yet settled. An Active bet locks
 * first. Corrections append compensating ledger entries. The server doesn't ask for a reason. Each override is
 * recorded on the bet and posted in chat, naming the host.
 */
@Service
class HostOverrideService {
    enum Outcome { OVERRIDDEN, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    static class PointsSettled extends RuntimeException {}

    static class InvalidChoice extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SportsBetLoader loader;
    private final BetLockService locks;
    private final BetResolutionWriter writer;
    private final ChatMessageWriter chat;

    HostOverrideService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            SportsBetLoader loader, BetLockService locks, BetResolutionWriter writer, ChatMessageWriter chat) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.loader = loader;
        this.locks = locks;
        this.writer = writer;
        this.chat = chat;
    }

    @Transactional
    public Outcome override(Jwt jwt, UUID betId, String winningChoice, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (winningChoice != null && !PostgresText.valid(winningChoice)) throw new InvalidChoice();
        UUID actor = UUID.fromString(jwt.getSubject());
        List<UUID> affected = loader.affectedUsers(betId);
        Map<UUID, String> locked = authentication.lockActiveWithTargets(jwt, affected == null ? List.of() : affected);
        if (locked == null) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return Outcome.FORBIDDEN;
        }
        if (affected == null) return Outcome.NOT_FOUND;
        SportsBetLoader.Loaded bet = loader.load(betId, new HashSet<>(locked.keySet()));
        authentication.requireUnexpired(jwt);
        if (bet == null) return Outcome.NOT_FOUND;
        String role = jdbc.query("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                (rs, row) -> rs.getString("role"), bet.bet().tableId(), actor).stream().findFirst().orElse(null);
        if (role == null) return Outcome.NOT_FOUND;
        if (!role.equals("HOST") || !SportsProposalService.MODES.contains(bet.mode())) return Outcome.FORBIDDEN;

        String request = betId + "|" + (winningChoice == null ? "wash" : "choice:" + winningChoice);
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, "bet.override.v1", request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (bet.pointsSettled()) throw new PointsSettled();
        if (winningChoice != null && !bet.terms().choices().contains(winningChoice)) throw new InvalidChoice();
        if (bet.status().equals("ACTIVE")) {
            locks.lockNow(betId);
            bet = loader.load(betId, new HashSet<>(locked.keySet()));
        }
        boolean correction = !bet.status().equals("PENDING");
        writer.apply(bet.bet(), winningChoice, winningChoice == null ? "Washed by a host" : null, actor, correction);
        jdbc.update("UPDATE bets SET overridden_by = ?, overridden_at = clock_timestamp() WHERE id = ?", actor, betId);
        String question = jdbc.queryForObject("SELECT question FROM bets WHERE id = ?", String.class, betId);
        chat.attributedSystem(bet.bet().tableId(), "A host " + (winningChoice == null
                ? "washed “" + question + "”" : "set “" + question + "” to " + winningChoice), "bet_override", actor);
        authentication.requireUnexpired(jwt);
        return Outcome.OVERRIDDEN;
    }
}
