package com.p2picks.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxCleanupIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private OutboxCleanup cleanup;

    @Test
    void deletesOnlyPublishedRowsOlderThanTheRetention() {
        UUID oldPublished = insert("now() - interval '8 days'");
        UUID recentPublished = insert("now() - interval '6 days'");
        UUID oldUnpublished = insert(null);
        jdbc.update("UPDATE outbox SET created_at = now() - interval '30 days' WHERE id = ?", oldUnpublished);

        assertThat(cleanup.deletePublishedOlderThan(Duration.ofDays(7))).isPositive();
        assertThat(exists(oldPublished)).isFalse();
        assertThat(exists(recentPublished)).isTrue();
        assertThat(exists(oldUnpublished)).as("unpublished rows are never deleted").isTrue();
    }

    private UUID insert(String publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, published_at) VALUES "
                + "(?, 'test', ?, 'test.cleanup.v1', '{}'::jsonb, " + (publishedAt == null ? "NULL" : publishedAt) + ")",
                id, id.toString());
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM outbox WHERE id = ?)",
                Boolean.class, id));
    }
}
