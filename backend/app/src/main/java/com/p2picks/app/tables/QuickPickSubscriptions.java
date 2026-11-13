package com.p2picks.app.tables;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class QuickPickSubscriptions {
    private record Subscription(UUID subscriber, UUID source) {}
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    QuickPickSubscriptions(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
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
        outbox.append(OutboxEvent.TABLE_QUICKPICKS_CHANGED, tableId, Map.of("tableId", tableId));
    }
}
