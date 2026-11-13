package com.p2picks.app.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the transactional outbox. Events must be appended inside the transaction that
 * makes the change they describe, so they commit or roll back together.
 */
@Component
public class Outbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param aggregateId identifies the aggregate the event is ordered by (see {@link OutboxEvent})
     * @param payload JSON object fields: strings, or numbers such as {@code BigInteger} cents
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event, Object aggregateId, Map<String, ?> payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unserializable outbox payload for " + event, exception);
        }
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), event.aggregateType(), aggregateId.toString(), event.eventType(), body);
    }
}
