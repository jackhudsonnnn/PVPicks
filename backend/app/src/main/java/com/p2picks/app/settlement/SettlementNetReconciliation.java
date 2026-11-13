package com.p2picks.app.settlement;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class SettlementNetReconciliation {
    record Snapshot(UUID userId, UUID otherId, BigInteger net, boolean inFlight, int openLines) {}
    private record Line(UUID payerId, long amount, String status, boolean requested) {}

    private final JdbcTemplate jdbc;
    private final SettlementReviewService reviews;
    private final Outbox outbox;

    SettlementNetReconciliation(JdbcTemplate jdbc, SettlementReviewService reviews, Outbox outbox) {
        this.jdbc = jdbc;
        this.reviews = reviews;
        this.outbox = outbox;
    }

    // Both users must already be locked in PostgreSQL UUID order.
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot capture(UUID userId, UUID otherId) {
        if (userId.equals(otherId)) throw new IllegalArgumentException("Settlement pair must be distinct");
        List<Line> lines = jdbc.query("""
                SELECT payer_id, amount_cents, status, forgive_requested_at IS NOT NULL AS requested FROM settlement_lines
                WHERE ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                  AND status NOT IN ('paid', 'forgiven')
                ORDER BY id FOR UPDATE
                """, (rs, row) -> new Line(rs.getObject("payer_id", UUID.class),
                        rs.getLong("amount_cents"), rs.getString("status"), rs.getBoolean("requested")),
                userId, otherId, otherId, userId);
        BigInteger net = BigInteger.ZERO;
        boolean inFlight = false;
        for (Line line : lines) {
            BigInteger amount = BigInteger.valueOf(line.amount());
            net = net.add(line.payerId().equals(userId) ? amount.negate() : amount);
            inFlight |= line.status().equals("sent") || line.status().equals("forgive_asked") || line.requested();
        }
        return new Snapshot(userId, otherId, net, inFlight, lines.size());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(Snapshot before) {
        Snapshot after = capture(before.userId(), before.otherId());
        if (after.openLines() == 0 || before.net().equals(after.net())) return;
        if (!before.inFlight()) {
            reviews.refreshPending(before.userId(), before.otherId());
            return;
        }
        jdbc.update("""
                UPDATE settlement_lines SET status = 'unpaid', forgive_requested_at = NULL,
                    revision = revision + 1, updated_at = clock_timestamp()
                WHERE ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                  AND (status IN ('sent', 'forgive_asked')
                       OR (status NOT IN ('paid', 'forgiven') AND forgive_requested_at IS NOT NULL))
                """, before.userId(), before.otherId(), before.otherId(), before.userId());
        reviews.recordReset(before.userId(), before.otherId());
        outbox.append(OutboxEvent.SETTLEMENT_NET_CHANGED, before.userId() + ":" + before.otherId(),
                Map.of("userId", before.userId(), "otherUserId", before.otherId(),
                "beforeCents", before.net(), "afterCents", after.net()));
    }
}
