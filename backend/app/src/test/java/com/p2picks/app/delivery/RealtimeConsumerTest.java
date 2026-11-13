package com.p2picks.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeConsumerTest {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TABLE = UUID.randomUUID();
    private static final UUID BET = UUID.randomUUID();
    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID PAYEE = UUID.randomUUID();

    @Test
    void betEventsInvalidateTheBetForTableMembers() {
        UUID id = UUID.randomUUID();
        for (String type : List.of("bet.created.v1", "bet.pick_count.changed.v1", "bet.locked.v1", "bet.resolved.v1")) {
            List<RealtimeConsumer.Route> routes = RealtimeConsumer.routes(
                    event(id, type, Map.of("tableId", TABLE, "betId", BET)), settlement -> Set.of());
            assertThat(routes).singleElement().satisfies(route -> {
                assertThat(route.tableId()).isEqualTo(TABLE);
                assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.BETS);
                assertThat(route.invalidation().type()).isEqualTo("bet.changed");
                assertThat(route.invalidation().resourceId()).isEqualTo(BET);
                assertValid(route.invalidation());
            });
        }
    }

    @Test
    void pointsSettlementInvalidatesTheBetListAndEachPartysReceipts() {
        UUID settlement = UUID.randomUUID();
        List<RealtimeConsumer.Route> routes = RealtimeConsumer.routes(
                event(UUID.randomUUID(), "settlement.created.v1", Map.of("tableId", TABLE, "settlementId", settlement)),
                id -> id.equals(settlement) ? Set.of(PAYER, PAYEE) : Set.of());
        assertThat(routes).hasSize(3);
        assertThat(routes.getFirst().invalidation().type()).isEqualTo("bets.changed");
        assertThat(routes.subList(1, 3)).extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
        routes.forEach(route -> assertValid(route.invalidation()));
    }

    @Test
    void privateSettlementChangesReachOnlyTheTwoPartiesWithoutAmounts() {
        List<RealtimeConsumer.Route> routes = RealtimeConsumer.routes(event(UUID.randomUUID(),
                "settlement.net.changed.v1",
                Map.of("userId", PAYER, "otherUserId", PAYEE, "beforeCents", 100, "afterCents", -25)), id -> Set.of());
        assertThat(routes).extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
        for (RealtimeConsumer.Route route : routes) {
            JsonNode body = JSON.valueToTree(route.invalidation());
            assertThat(body.toString()).doesNotContain("100").doesNotContain("-25").doesNotContain(PAYEE.toString());
            assertValid(route.invalidation());
        }
        assertThat(RealtimeConsumer.routes(event(UUID.randomUUID(), "settlement.line.changed.v1",
                Map.of("lineId", UUID.randomUUID(), "payerId", PAYER, "payeeId", PAYEE)), id -> Set.of()))
                .extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
    }

    @Test
    void chatEventsInvalidateTheChatWithoutMessageIdentity() {
        UUID message = UUID.randomUUID();
        List<RealtimeConsumer.Route> routes = RealtimeConsumer.routes(event(UUID.randomUUID(), "chat.message.created.v1",
                Map.of("tableId", TABLE, "messageId", message, "kind", "text")), id -> Set.of());
        assertThat(routes).singleElement().satisfies(route -> {
            assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.CHAT);
            assertThat(JSON.valueToTree(route.invalidation()).toString()).doesNotContain(message.toString());
            assertValid(route.invalidation());
        });
    }

    @Test
    void eventsWithoutADestinationAreIgnoredAndRedeliveryKeepsTheEventId() {
        assertThat(RealtimeConsumer.routes(event(UUID.randomUUID(), "table.member.removed.v1",
                Map.of("tableId", TABLE, "userId", PAYER)), id -> Set.of())).isEmpty();
        assertThat(RealtimeConsumer.routes(event(UUID.randomUUID(), "unknown.v1", Map.of()), id -> Set.of())).isEmpty();
        UUID id = UUID.randomUUID();
        ObjectNode event = event(id, "bet.locked.v1", Map.of("tableId", TABLE, "betId", BET));
        assertThat(RealtimeConsumer.routes(event, x -> Set.of()).getFirst().invalidation().eventId())
                .isEqualTo(RealtimeConsumer.routes(event, x -> Set.of()).getFirst().invalidation().eventId());
    }

    private static ObjectNode event(UUID id, String type, Map<String, ?> payload) {
        ObjectNode event = JSON.createObjectNode().put("id", id.toString()).put("eventType", type)
                .put("createdAt", "2026-11-13T12:00:00Z");
        event.set("payload", JSON.valueToTree(payload));
        return event;
    }

    private static void assertValid(Invalidation invalidation) {
        assertThat(RealtimeSchema.SCHEMA.validate(JSON.valueToTree(invalidation))).isEmpty();
    }
}
