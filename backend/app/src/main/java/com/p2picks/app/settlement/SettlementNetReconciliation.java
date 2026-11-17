package com.p2picks.app.settlement;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * When a new points settlement changes what two people owe each other, both must review the new balance before
 * settling again (domain rules, "Settle-ups"), so nobody settles against a total that's out of date. A pair's first
 * receipts need no review: there was no earlier total to be wrong about.
 */
@Component
class SettlementNetReconciliation {
    record Snapshot(UUID userId, UUID otherId, BigInteger net, int openLines) {}

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
        BigInteger[] net = {BigInteger.ZERO};
        int[] count = {0};
        jdbc.query("""
                SELECT payer_id, amount_cents FROM settlement_lines
                WHERE ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?)) AND status = 'unpaid'
                ORDER BY id FOR UPDATE
                """, rs -> {
                    BigInteger amount = BigInteger.valueOf(rs.getLong("amount_cents"));
                    net[0] = net[0].add(rs.getObject("payer_id", UUID.class).equals(userId) ? amount.negate() : amount);
                    count[0]++;
                }, userId, otherId, otherId, userId);
        return new Snapshot(userId, otherId, net[0], count[0]);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(Snapshot before) {
        Snapshot after = capture(before.userId(), before.otherId());
        if (after.openLines() == 0 || before.net().equals(after.net())) return;
        if (before.openLines() == 0) {
            reviews.refreshPending(before.userId(), before.otherId());
            return;
        }
        reviews.recordReset(before.userId(), before.otherId());
        outbox.append(OutboxEvent.SETTLEMENT_NET_CHANGED, before.userId() + ":" + before.otherId(),
                Map.of("userId", before.userId(), "otherUserId", before.otherId(),
                "beforeCents", before.net(), "afterCents", after.net()));
    }
}
