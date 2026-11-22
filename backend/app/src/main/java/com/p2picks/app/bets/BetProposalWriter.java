package com.p2picks.app.bets;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.DebtQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The proposal steps every bet mode shares (domain rules, "Propose"): lock and recheck the account, session, table
 * and membership; claim the retry key; check betting settings and the debt cap; insert the Active bet with a
 * database-assigned deadline and its outbox event. Modes supply only the validated bet row.
 */
@Component
public class BetProposalWriter {
    public enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    public record Result(Outcome outcome, UUID betId) {}

    /** Which table switch, if any, the mode answers to beyond "hosts only bets". */
    enum Gate { NONE, U2PICK, JACKBLACK }

    /**
     * A validated bet to insert. {@code lockBy} is the latest moment picks may lock (a game's start); the deadline
     * is the pick window's end or {@code lockBy}, whichever is first, ignoring a {@code lockBy} already past.
     */
    record NewBet(String mode, String gameId, String configJson, String question, String choicesJson,
                  long wagerCents, boolean forFun, int durationSec, String settlerRule, Boolean includesOvertime,
                  Instant lockBy, long riskCents) {
        NewBet {
            Objects.requireNonNull(mode, "mode");
            if (wagerCents < 0 || (!forFun && (wagerCents < 10 || wagerCents > 1000))) {
                throw new IllegalArgumentException("Invalid wager");
            }
            if (riskCents < 0) throw new IllegalArgumentException("Invalid risk");
            if (durationSec < 30 || durationSec > 600) throw new IllegalArgumentException("Invalid pick window");
        }

        /** A bet whose proposer risks its wager (every mode but Jackblack, where each player risks their worst case). */
        NewBet(String mode, String gameId, String configJson, String question, String choicesJson, long wagerCents,
                boolean forFun, int durationSec, String settlerRule, Boolean includesOvertime, Instant lockBy) {
            this(mode, gameId, configJson, question, choicesJson, wagerCents, forFun, durationSec, settlerRule,
                    includesOvertime, lockBy, wagerCents);
        }
    }

    static class DebtCapExceeded extends RuntimeException {}

    private final BetLiveStats liveStats;
    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;

    BetProposalWriter(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            DebtQuery debt, Outbox outbox, BetLiveStats liveStats) {
        this.liveStats = liveStats;
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
        this.outbox = outbox;
    }

    /**
     * Creates the bet. {@code bet} is built only after the retry key is claimed as new, so mode checks that depend on
     * live data (a game's status) see the state at creation; it may throw {@link IllegalArgumentException} or
     * {@link ModeUnavailable} to refuse.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result create(Jwt jwt, UUID tableId, String key, String operation, String fingerprint, Gate gate,
            Supplier<NewBet> bet) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        Objects.requireNonNull(tableId, "tableId");
        UUID actor = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return failure(Outcome.UNAUTHORIZED);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return failure(Outcome.FORBIDDEN);
        }
        List<Settings> tables = jdbc.query("""
                SELECT hosts_only_bets, u2pick_enabled, jackblack_access FROM tables
                WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> new Settings(rs.getBoolean("hosts_only_bets"),
                        rs.getBoolean("u2pick_enabled"), rs.getString("jackblack_access")), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return failure(Outcome.NOT_FOUND);
        }
        List<Member> members = jdbc.query("""
                SELECT role, can_bet FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> new Member(rs.getString("role"), rs.getBoolean("can_bet")), tableId, actor);
        authentication.requireUnexpired(jwt);
        if (members.isEmpty()) return failure(Outcome.NOT_FOUND);
        Member member = members.getFirst();
        if (!member.canBet()) return failure(Outcome.FORBIDDEN);

        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, operation, fingerprint,
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return failure(Outcome.CONFLICT);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            UUID betId = idempotency.responseReference(actor, key, operation);
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.REPLAYED, betId);
        }
        Settings settings = tables.getFirst();
        boolean host = member.role().equals("HOST");
        boolean gated = switch (gate) {
            case NONE -> false;
            case U2PICK -> !settings.u2pickEnabled();
            case JACKBLACK -> settings.jackblackAccess().equals("off")
                    || (settings.jackblackAccess().equals("hosts") && !host);
        };
        if (gated || (settings.hostsOnlyBets() && !host)) {
            idempotency.discardNewClaim(actor, key);
            authentication.requireUnexpired(jwt);
            return failure(Outcome.FORBIDDEN);
        }
        NewBet row = bet.get();
        if (!debt.canRiskForLockedUser(actor, row.riskCents(), row.forFun())) throw new DebtCapExceeded();
        UUID betId = UUID.randomUUID();
        jdbc.update("""
                WITH stamp AS MATERIALIZED (SELECT clock_timestamp() AS at)
                INSERT INTO bets (id, table_id, proposer_id, mode, game_id, config, question, choices,
                                  wager_cents, is_4fun, locks_at, created_at, settler_rule, includes_overtime)
                SELECT ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?,
                       CASE WHEN ?::timestamptz > stamp.at
                            THEN LEAST(stamp.at + (? * interval '1 second'), ?::timestamptz)
                            ELSE stamp.at + (? * interval '1 second') END,
                       stamp.at, ?, ? FROM stamp
                """, betId, tableId, actor, row.mode(), row.gameId(), row.configJson(), row.question(),
                row.choicesJson(), row.wagerCents(), row.forFun(),
                row.lockBy() == null ? null : Timestamp.from(row.lockBy()), row.durationSec(),
                row.lockBy() == null ? null : Timestamp.from(row.lockBy()), row.durationSec(),
                row.settlerRule(), row.includesOvertime());
        // The numbers a sports bet follows, as they were when it was proposed (ticket Details, "Live stats").
        if (row.gameId() != null) liveStats.capture(betId, "proposed");
        outbox.append(OutboxEvent.BET_CREATED, betId, Map.of("tableId", tableId, "betId", betId));
        idempotency.saveResponseReference(actor, key, operation, betId);
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.CREATED, betId);
    }

    /** A mode can't be proposed right now (for example its game is over); rolls the proposal back. */
    static class ModeUnavailable extends RuntimeException {
        ModeUnavailable(String message) {
            super(message);
        }
    }

    private static Result failure(Outcome outcome) {
        return new Result(outcome, null);
    }

    private record Settings(boolean hostsOnlyBets, boolean u2pickEnabled, String jackblackAccess) {}

    private record Member(String role, boolean canBet) {}
}
