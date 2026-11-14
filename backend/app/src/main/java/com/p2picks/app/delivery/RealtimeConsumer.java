package com.p2picks.app.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.OutboxEvent;
import java.util.ArrayList;
import java.util.HashSet;
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
            for (Route route : routes(json.readTree(value), access)) {
                if (route.tableFeed() != null) {
                    gateway.toTable(route.tableId(), route.tableFeed(), route.invalidation(), route.onlyUsers());
                } else if (route.userId() != null) {
                    gateway.toUser(route.userId(), route.destination(), route.invalidation());
                } else {
                    gateway.toSubscribers(route.destination(), route.invalidation());
                }
            }
        } catch (Exception exception) {
            // Delivery is best effort: a bad or undeliverable event must not block the ones behind it.
            log.warn("Skipped a realtime invalidation", exception);
        }
    }

    /**
     * Where an outbox event is delivered. Audiences are looked up at delivery time, so they reflect current
     * membership, invitations and blocks. Invalidations name at most a table and one resource; they never carry
     * picks, message content, names or amounts.
     */
    static List<Route> routes(JsonNode event, Audiences audiences) {
        OutboxEvent type = EVENTS.get(event.path("eventType").asText());
        if (type == null) return List.of();
        Routes routes = new Routes(UUID.fromString(event.path("id").asText()), event.path("createdAt").asText());
        JsonNode payload = event.path("payload");
        switch (type) {
            case BET_CREATED, BET_PICK_COUNT_CHANGED, BET_LOCKED, BET_RESOLVED -> {
                UUID table = uuid(payload, "tableId");
                routes.table(table, RealtimeGateway.TableFeed.BETS, Invalidation.BET_CHANGED,
                        uuid(payload, "betId"), null);
            }
            case SETTLEMENT_CREATED -> {
                routes.table(uuid(payload, "tableId"), RealtimeGateway.TableFeed.BETS, Invalidation.BETS_CHANGED,
                        null, null);
                routes.settlements(audiences.settlementParties(uuid(payload, "settlementId")));
            }
            case SETTLEMENT_LINE_CHANGED -> routes.settlements(Set.of(uuid(payload, "payerId"), uuid(payload, "payeeId")));
            case SETTLEMENT_GROUP_CHANGED, SETTLEMENT_NET_CHANGED ->
                    routes.settlements(Set.of(uuid(payload, "userId"), uuid(payload, "otherUserId")));
            case CHAT_MESSAGE_CREATED, CHAT_REACTION_CHANGED ->
                    routes.table(uuid(payload, "tableId"), RealtimeGateway.TableFeed.CHAT, Invalidation.CHAT_CHANGED,
                            null, null);
            case TABLE_CREATED -> routes.personal(audiences.tableMembers(uuid(payload, "tableId")),
                    Invalidation.TABLES_CHANGED, null);
            case TABLE_PROFILE_CHANGED -> {
                // The name and emoji also appear in table lists and in pending invitations.
                UUID table = uuid(payload, "tableId");
                routes.tableView(table, null);
                routes.personal(audiences.tableMembers(table), Invalidation.TABLES_CHANGED, null);
                routes.personal(audiences.pendingInvitees(table), Invalidation.INVITATIONS_CHANGED, null);
            }
            case TABLE_SETTINGS_CHANGED, TABLE_REMAINDER_CHANGED, TABLE_QUICKPICKS_CHANGED ->
                    routes.tableView(uuid(payload, "tableId"), null);
            case TABLE_HOST_CHANGED -> {
                UUID table = uuid(payload, "tableId");
                routes.tableView(table, null);
                routes.personal(audiences.tableMembers(table), Invalidation.TABLES_CHANGED, null);
            }
            case TABLE_MEMBER_JOINED -> {
                routes.tableView(uuid(payload, "tableId"), null);
                routes.personal(Set.of(uuid(payload, "userId")), Invalidation.TABLES_CHANGED, null);
            }
            case TABLE_MEMBER_REMOVED -> {
                // Table-feed deliveries recheck membership, so the removed user no longer receives them.
                routes.tableView(uuid(payload, "tableId"), null);
                routes.personal(Set.of(uuid(payload, "removedUserId")), Invalidation.TABLES_CHANGED, null);
            }
            case TABLE_MEMBER_PERMISSION_CHANGED -> {
                // Only current hosts and the affected member may learn that a permission changed.
                UUID table = uuid(payload, "tableId");
                Set<UUID> audience = new HashSet<>(audiences.tableHosts(table));
                audience.add(uuid(payload, "userId"));
                routes.tableView(table, audience);
            }
            // Names only the table: who threw at whom comes from the block-filtered HTTP read.
            case TABLE_TOMATO_THROWN -> routes.table(uuid(payload, "tableId"), RealtimeGateway.TableFeed.VIEW,
                    Invalidation.TOMATOES_CHANGED, null, null);
            case TABLE_ARCHIVED -> routes.personal(audiences.archiveMembers(uuid(payload, "tableId")),
                    Invalidation.TABLES_CHANGED, null);
            case TABLE_INVITATION_CREATED -> routes.personal(
                    Set.of(UUID.fromString(event.path("aggregateId").asText())), Invalidation.INVITATIONS_CHANGED, null);
            case SPORTS_GAMES_CHANGED -> routes.everyone(RealtimeGateway.TICKER_DESTINATION,
                    Invalidation.TICKER_CHANGED);
            case USER_PROFILE_CHANGED -> {
                UUID user = uuid(payload, "userId");
                Set<UUID> audience = new HashSet<>(audiences.profileAudience(user));
                audience.add(user);
                routes.personal(audience, Invalidation.PROFILE_CHANGED, user);
            }
        }
        return routes.list;
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asText());
    }

    record Route(UUID tableId, RealtimeGateway.TableFeed tableFeed, Set<UUID> onlyUsers, UUID userId,
                 String destination, Invalidation invalidation) {}

    private static final class Routes {
        private final UUID outboxId;
        private final String occurredAt;
        private final List<Route> list = new ArrayList<>();

        Routes(UUID outboxId, String occurredAt) {
            this.outboxId = outboxId;
            this.occurredAt = occurredAt;
        }

        void table(UUID table, RealtimeGateway.TableFeed feed, String type, UUID resource, Set<UUID> onlyUsers) {
            list.add(new Route(table, feed, onlyUsers, null, null,
                    Invalidation.of(outboxId, type, occurredAt, table, resource)));
        }

        void tableView(UUID table, Set<UUID> onlyUsers) {
            table(table, RealtimeGateway.TableFeed.VIEW, Invalidation.TABLE_CHANGED, null, onlyUsers);
        }

        void settlements(Set<UUID> users) {
            for (UUID user : users) {
                list.add(new Route(null, null, null, user, RealtimeGateway.SETTLEMENTS_DESTINATION,
                        Invalidation.of(outboxId, Invalidation.SETTLEMENTS_CHANGED, occurredAt, null, null)));
            }
        }

        void everyone(String destination, String type) {
            list.add(new Route(null, null, null, null, destination,
                    Invalidation.of(outboxId, type, occurredAt, null, null)));
        }

        void personal(Set<UUID> users, String type, UUID resource) {
            for (UUID user : users) {
                list.add(new Route(null, null, null, user, RealtimeGateway.PERSONAL_DESTINATION,
                        Invalidation.of(outboxId, type, occurredAt, null, resource)));
            }
        }
    }
}
