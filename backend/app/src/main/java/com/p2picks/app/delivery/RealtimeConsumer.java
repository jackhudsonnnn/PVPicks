package com.p2picks.app.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.OutboxEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns published outbox events into realtime invalidations. Each instance consumes every event with its own
 * consumer group and serves only its own WebSocket sessions; it starts from the latest offset because clients
 * refetch on (re)connect. Invalidations are idempotent, so no processed-event marker is needed.
 */
@Component
class RealtimeConsumer {
    private static final Logger log = LoggerFactory.getLogger(RealtimeConsumer.class);
    private static final Map<String, OutboxEvent> EVENTS = Arrays.stream(OutboxEvent.values())
            .collect(Collectors.toMap(OutboxEvent::eventType, Function.identity()));

    private final ObjectMapper json;
    private final RealtimeGateway gateway;
    private final RealtimeAccess access;

    RealtimeConsumer(ObjectMapper json, RealtimeGateway gateway, RealtimeAccess access) {
        this.json = json;
        this.gateway = gateway;
        this.access = access;
    }

    @KafkaListener(id = "realtime-invalidations", topics = OutboxRelay.TOPIC,
            groupId = "p2picks-realtime-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=${pvpicks.realtime.offset-reset:latest}")
    void onEvent(String value) {
        try {
            for (Route route : routes(json.readTree(value), access::settlementParties)) {
                if (route.tableFeed() != null) {
                    gateway.toTable(route.tableId(), route.tableFeed(), route.invalidation());
                } else {
                    gateway.toUser(route.userId(), route.invalidation());
                }
            }
        } catch (Exception exception) {
            // Delivery is best effort: a bad or undeliverable event must not block the ones behind it.
            log.warn("Skipped a realtime invalidation", exception);
        }
    }

    /** Where an outbox event is delivered; pure apart from looking up a settlement's parties. */
    static List<Route> routes(JsonNode event, Function<UUID, Set<UUID>> settlementParties) {
        OutboxEvent type = EVENTS.get(event.path("eventType").asText());
        if (type == null) return List.of();
        UUID id = UUID.fromString(event.path("id").asText());
        String at = event.path("createdAt").asText();
        JsonNode payload = event.path("payload");
        List<Route> routes = new ArrayList<>();
        switch (type) {
            case BET_CREATED, BET_PICK_COUNT_CHANGED, BET_LOCKED, BET_RESOLVED -> {
                UUID table = uuid(payload, "tableId");
                routes.add(Route.table(table, RealtimeGateway.TableFeed.BETS,
                        Invalidation.of(id, Invalidation.BET_CHANGED, at, table, uuid(payload, "betId"))));
            }
            case SETTLEMENT_CREATED -> {
                UUID table = uuid(payload, "tableId");
                routes.add(Route.table(table, RealtimeGateway.TableFeed.BETS,
                        Invalidation.of(id, Invalidation.BETS_CHANGED, at, table, null)));
                for (UUID party : settlementParties.apply(uuid(payload, "settlementId"))) {
                    routes.add(Route.user(party, Invalidation.of(id, Invalidation.SETTLEMENTS_CHANGED, at, null, null)));
                }
            }
            case SETTLEMENT_LINE_CHANGED -> {
                for (String field : List.of("payerId", "payeeId")) {
                    routes.add(Route.user(uuid(payload, field),
                            Invalidation.of(id, Invalidation.SETTLEMENTS_CHANGED, at, null, null)));
                }
            }
            case SETTLEMENT_GROUP_CHANGED, SETTLEMENT_NET_CHANGED -> {
                for (String field : List.of("userId", "otherUserId")) {
                    routes.add(Route.user(uuid(payload, field),
                            Invalidation.of(id, Invalidation.SETTLEMENTS_CHANGED, at, null, null)));
                }
            }
            case CHAT_MESSAGE_CREATED, CHAT_REACTION_CHANGED -> {
                UUID table = uuid(payload, "tableId");
                routes.add(Route.table(table, RealtimeGateway.TableFeed.CHAT,
                        Invalidation.of(id, Invalidation.CHAT_CHANGED, at, table, null)));
            }
            default -> {
                // Table, membership, profile and invitation events have no realtime destination yet.
            }
        }
        return routes;
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    record Route(UUID tableId, RealtimeGateway.TableFeed tableFeed, UUID userId, Invalidation invalidation) {
        static Route table(UUID tableId, RealtimeGateway.TableFeed feed, Invalidation invalidation) {
            return new Route(tableId, feed, null, invalidation);
        }

        static Route user(UUID userId, Invalidation invalidation) {
            return new Route(null, null, userId, invalidation);
        }
    }
}
