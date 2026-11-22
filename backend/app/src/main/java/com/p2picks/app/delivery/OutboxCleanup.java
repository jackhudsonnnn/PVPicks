package com.p2picks.app.delivery;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes published outbox rows once they are older than the retention period. Unpublished rows are never
 * deleted. Kafka, not the outbox, is the replayable stream, so published rows are only kept for debugging.
 */
@Component
class OutboxCleanup {
    static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbc;

    OutboxCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes in batches so a large backlog does not hold one long transaction; returns rows deleted. */
    int deletePublishedOlderThan(Duration retention) {
        if (retention.isNegative()) throw new IllegalArgumentException("Retention must not be negative");
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    DELETE FROM outbox WHERE id IN (
                        SELECT id FROM outbox
                        WHERE published_at IS NOT NULL AND published_at < now() - make_interval(secs => ?)
                        ORDER BY published_at LIMIT ?)
                    """, retention.toSeconds(), BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
