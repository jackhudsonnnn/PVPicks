package com.p2picks.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.IntegrationTestSupport;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayIntegrationTest extends IntegrationTestSupport {
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Outbox outbox;

    @Test
    void publishesCommittedEventsAndMarksThemOnlyAfterTheBrokerAcknowledges() throws Exception {
        UUID table = UUID.randomUUID();
        UUID bet = UUID.randomUUID();
        UUID event = append(OutboxEvent.BET_LOCKED, bet, Map.of("tableId", table, "betId", bet));

        assertThat(relay.publishPending()).isPositive();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?", Boolean.class, event))
                .isTrue();
        ConsumerRecord<String, String> record = find(event);
        assertThat(record.key()).isEqualTo("bet:" + bet);
        JsonNode message = json.readTree(record.value());
        assertThat(message.path("eventType").asText()).isEqualTo("bet.locked.v1");
        assertThat(message.path("payload").path("tableId").asText()).isEqualTo(table.toString());
        assertThat(relay.publishPending()).as("published rows are not sent again").isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesEventsUnpublishedWhenTheBrokerFails() {
        relay.publishPending();
        UUID event = append(OutboxEvent.BET_LOCKED, UUID.randomUUID(),
                Map.of("tableId", UUID.randomUUID(), "betId", UUID.randomUUID()));
        KafkaTemplate<String, String> down = mock(KafkaTemplate.class);
        when(down.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        OutboxRelay failing = new OutboxRelay(jdbc, down, json, new TransactionTemplate(transactions));

        assertThatThrownBy(failing::publishPending).hasMessageContaining("did not acknowledge");
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox WHERE id = ?", Boolean.class, event))
                .isTrue();
        assertThat(relay.publishPending()).isPositive();
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox WHERE id = ?", Boolean.class, event))
                .isFalse();
    }

    private UUID append(OutboxEvent type, UUID aggregate, Map<String, ?> payload) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> outbox.append(type, aggregate, payload));
        return jdbc.queryForObject("""
                SELECT id FROM outbox WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at DESC LIMIT 1
                """, UUID.class, aggregate.toString(), type.eventType());
    }

    private ConsumerRecord<String, String> find(UUID event) {
        Properties properties = new Properties();
        properties.putAll(kafkaAdmin.getConfigurationProperties());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(OutboxRelay.TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(event.toString())) return record;
                }
            }
        }
        throw new AssertionError("Event " + event + " was not published");
    }
}
