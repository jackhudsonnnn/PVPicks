package com.p2picks.app.bets;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.shared.kernel.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class BetResolutionWriter {
    record LockedBet(UUID id, UUID tableId, long wagerCents, boolean forFun, List<String> choices,
                     List<PayoutCalculator.Participant> participants) {}

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final ObjectProvider<BetLiveStats> liveStats;

    BetResolutionWriter(JdbcTemplate jdbc, Outbox outbox, ObjectProvider<BetLiveStats> liveStats) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.liveStats = liveStats;
    }

    // The caller must authorize the outcome and lock all affected users, the table and the bet.
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(LockedBet bet, String winningChoice, UUID calledBy) {
        apply(bet, winningChoice, null, calledBy, false);
    }

    /**
     * Resolves a Pending bet, or (with {@code correction}) changes the result of a resolved bet whose points aren't
     * settled. {@code forcedWash} washes with that reason regardless of picks (a canceled game, a push).
     * Corrections append compensating ledger entries so each user's sum becomes the new result; earlier entries are
     * never edited. The caller must authorize the outcome and lock all affected users, the table and the bet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(LockedBet bet, String winningChoice, String forcedWash, UUID calledBy, boolean correction) {
        Map<UUID, Long> posted = new java.util.HashMap<>();
        jdbc.query("SELECT user_id, sum(delta_cents) AS total FROM ledger_entries WHERE bet_id = ? GROUP BY user_id",
                rs -> {
                    posted.put(rs.getObject("user_id", UUID.class), rs.getLong("total"));
                }, bet.id());
        if (!correction && !posted.isEmpty()) throw new IllegalStateException("Pending bet already has ledger entries");
        String status;
        String washReason;
        Map<UUID, Money> deltas;
        if (forcedWash != null) {
            status = "WASHED";
            washReason = forcedWash;
            deltas = Map.of();
            winningChoice = null;
        } else {
            PayoutCalculator.Result result = PayoutCalculator.settle(bet.id(), bet.participants(),
                    new Money(bet.wagerCents()), bet.forFun(), bet.choices(), winningChoice);
            if (result instanceof PayoutCalculator.Result.Resolved resolved) {
                status = "SETTLED";
                washReason = null;
                deltas = resolved.deltas();
            } else if (result instanceof PayoutCalculator.Result.Washed washed) {
                status = "WASHED";
                washReason = washReason(washed.reason());
                deltas = Map.of();
            } else {
                throw new IllegalStateException("Unknown payout result");
            }
        }
        int updated = jdbc.update("""
                UPDATE bets SET status = ?, closed_at = clock_timestamp(), called_by = ?,
                    winning_choice = ?, wash_reason = ?, version = version + 1
                WHERE id = ? AND table_id = ?
                  AND (status = 'PENDING' OR (? AND status IN ('SETTLED', 'WASHED') AND points_settled_at IS NULL))
                """, status, calledBy, winningChoice, washReason, bet.id(), bet.tableId(), correction);
        if (updated != 1) throw new IllegalStateException("Bet changed during resolution");
        // A sports ticket's tracked stats stop here: Details shows them as they were when it was called.
        liveStats.ifAvailable(stats -> stats.captureResult(bet.id()));
        Map<UUID, Long> changes = new java.util.LinkedHashMap<>();
        deltas.forEach((user, money) -> changes.put(user, money.cents()));
        posted.forEach((user, total) -> changes.merge(user, -total, Math::addExact));
        for (Map.Entry<UUID, Long> entry : changes.entrySet()) {
            if (entry.getValue() == 0) continue;
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), bet.tableId(), bet.id(), entry.getKey(), entry.getValue());
        }
        outbox.append(OutboxEvent.BET_RESOLVED, bet.id(),
                Map.of("tableId", bet.tableId(), "betId", bet.id()));
    }

    private static String washReason(PayoutCalculator.WashReason reason) {
        return switch (reason) {
            case INVALID_OUTCOME -> "Outcome fell outside the available choices";
            case INSUFFICIENT_PLAYERS -> "Not enough players in the pot";
            case SAME_SIDE -> "Everyone picked the same side";
            case NO_WINNER -> "Nobody picked the winning choice";
        };
    }
}
