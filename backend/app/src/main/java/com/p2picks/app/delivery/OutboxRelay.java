package com.p2picks.app.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unpublished outbox rows to Kafka, oldest first, and marks them published only after the
 * broker acknowledges every send. A failed batch rolls back and is retried, so delivery is at least once.
 * {@code FOR UPDATE SKIP LOCKED} lets several relays share the table without publishing a row twice
 * concurrently. Ordering follows {@code created_at}, not commit order, so consumers must not rely on it.
 */
@Component
class OutboxRelay {
    static final String TOPIC = "p2picks.outbox.v1";
    static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, ObjectMapper json,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
        this.transactions = transactions;
    }

    /** Publishes every pending row, one batch per transaction; returns the number published. */
    int publishPending() {
        int total = 0;
        int published;
        do {
            published = publishBatch();
            total += published;
        } while (published == BATCH_SIZE);
        return total;
    }

    private int publishBatch() {
        Integer published = transactions.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, created_at
                    FROM outbox WHERE published_at IS NULL
                    ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                    rs.getObject("created_at", OffsetDateTime.class)), BATCH_SIZE);
            if (rows.isEmpty()) return 0;
            CompletableFuture<?>[] sends = rows.stream()
                    .map(row -> kafka.send(TOPIC, row.aggregateType() + ":" + row.aggregateId(), envelope(row)))
                    .toArray(CompletableFuture[]::new);
            awaitAcknowledgement(sends);
            jdbc.batchUpdate("UPDATE outbox SET published_at = now() WHERE id = ?", rows, rows.size(),
                    (statement, row) -> statement.setObject(1, row.id()));
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    private String envelope(Row row) {
        try {
            ObjectNode message = json.createObjectNode()
                    .put("id", row.id().toString())
                    .put("aggregateType", row.aggregateType())
                    .put("aggregateId", row.aggregateId())
                    .put("eventType", row.eventType())
                    .put("createdAt", row.createdAt().toString());
            message.set("payload", json.readTree(row.payload()));
            return json.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Outbox row " + row.id() + " has an unreadable payload", exception);
        }
    }

    private static void awaitAcknowledgement(CompletableFuture<?>[] sends) {
        try {
            CompletableFuture.allOf(sends).get(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox events", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Kafka did not acknowledge outbox events", exception);
        }
    }

    private record Row(UUID id, String aggregateType, String aggregateId, String eventType, String payload,
                       OffsetDateTime createdAt) {}
}
