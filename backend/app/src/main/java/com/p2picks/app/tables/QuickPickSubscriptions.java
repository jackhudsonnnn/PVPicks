package com.p2picks.app.tables;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class QuickPickSubscriptions {
    private record Subscription(UUID subscriber, UUID source) {}
    private final JdbcTemplate jdbc;

    QuickPickSubscriptions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void disconnect(UUID tableId, boolean outgoing) {
        List<Subscription> subscriptions = jdbc.query("""
                SELECT subscriber_table_id, source_table_id FROM quickpick_subscriptions
                WHERE source_table_id = ? OR (? AND subscriber_table_id = ?)
                ORDER BY subscriber_table_id, source_table_id FOR UPDATE
                """, (rs, row) -> new Subscription(rs.getObject("subscriber_table_id", UUID.class),
                        rs.getObject("source_table_id", UUID.class)), tableId, outgoing, tableId);
        for (Subscription subscription : subscriptions) {
            jdbc.update("""
                    DELETE FROM quickpick_subscriptions WHERE subscriber_table_id = ? AND source_table_id = ?
                    """, subscription.subscriber(), subscription.source());
            if (!subscription.subscriber().equals(tableId)) changedEvent(subscription.subscriber());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changedEvent(UUID tableId) {
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'table', ?, 'table.quickpicks.changed.v1', jsonb_build_object('tableId', ?::text))
                """, UUID.randomUUID(), tableId.toString(), tableId.toString());
    }
}
