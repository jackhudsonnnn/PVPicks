package com.p2picks.app.bets;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.shared.kernel.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class BetResolutionWriter {
    record LockedBet(UUID id, UUID tableId, long wagerCents, boolean forFun, List<String> choices,
                     PayoutCalculator.Remainder remainder, List<PayoutCalculator.Participant> participants) {}

    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    BetResolutionWriter(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    // The caller must authorize the outcome and lock all affected users, the table and the bet.
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolve(LockedBet bet, String winningChoice, UUID calledBy) {
        Boolean posted = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ledger_entries WHERE bet_id = ?)",
                Boolean.class, bet.id());
        if (!Boolean.FALSE.equals(posted)) throw new IllegalStateException("Pending bet already has ledger entries");
        PayoutCalculator.Result result = PayoutCalculator.settle(bet.id(), bet.participants(),
                new Money(bet.wagerCents()), bet.forFun(), bet.choices(), winningChoice, bet.remainder());
        String status;
        String washReason;
        Map<UUID, Money> deltas;
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
        int updated = jdbc.update("""
                UPDATE bets SET status = ?, closed_at = clock_timestamp(), called_by = ?,
                    winning_choice = ?, wash_reason = ?, version = version + 1
                WHERE id = ? AND table_id = ? AND status = 'PENDING'
                """, status, calledBy, winningChoice, washReason, bet.id(), bet.tableId());
        if (updated != 1) throw new IllegalStateException("Pending bet changed during resolution");
        for (Map.Entry<UUID, Money> entry : deltas.entrySet()) {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), bet.tableId(), bet.id(), entry.getKey(), entry.getValue().cents());
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
