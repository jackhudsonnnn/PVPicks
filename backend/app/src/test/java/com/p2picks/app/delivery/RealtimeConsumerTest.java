package com.p2picks.app.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.p2picks.app.common.OutboxEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeConsumerTest {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final UUID TABLE = UUID.randomUUID();
    private static final UUID BET = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID PAYEE = UUID.randomUUID();
    private static final UUID INVITEE = UUID.randomUUID();
    private static final UUID FORMER = UUID.randomUUID();
    private static final UUID SETTLEMENT = UUID.randomUUID();

    /** A fixed table with a host, a member, a pending invitee and an archive snapshot. */
    private static final Audiences AUDIENCES = new Audiences() {
        public Set<UUID> settlementParties(UUID settlement) {
            return settlement.equals(SETTLEMENT) ? Set.of(PAYER, PAYEE) : Set.of();
        }

        public Set<UUID> tableMembers(UUID table) {
            return Set.of(HOST, MEMBER);
        }

        public Set<UUID> tableHosts(UUID table) {
            return Set.of(HOST);
        }

        public Set<UUID> pendingInvitees(UUID table) {
            return Set.of(INVITEE);
        }

        public Set<UUID> archiveMembers(UUID table) {
            return Set.of(HOST, FORMER);
        }

        public Set<UUID> profileAudience(UUID user) {
            return Set.of(HOST);
        }
    };

    @Test
    void betEventsInvalidateTheBetForTableMembers() {
        for (OutboxEvent type : List.of(OutboxEvent.BET_CREATED, OutboxEvent.BET_PICK_COUNT_CHANGED,
                OutboxEvent.BET_LOCKED, OutboxEvent.BET_RESOLVED)) {
            assertThat(routes(type, Map.of("tableId", TABLE, "betId", BET))).singleElement().satisfies(route -> {
                assertThat(route.tableId()).isEqualTo(TABLE);
                assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.BETS);
                assertThat(route.invalidation().type()).isEqualTo("bet.changed");
                assertThat(route.invalidation().resourceId()).isEqualTo(BET);
            });
        }
    }

    @Test
    void pointsSettlementInvalidatesTheBetListAndEachPartysReceipts() {
        List<RealtimeConsumer.Route> routes = routes(OutboxEvent.SETTLEMENT_CREATED,
                Map.of("tableId", TABLE, "settlementId", SETTLEMENT));
        assertThat(routes).hasSize(3);
        assertThat(routes.getFirst().invalidation().type()).isEqualTo("bets.changed");
        assertThat(routes.subList(1, 3)).extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
        assertThat(routes.subList(1, 3)).extracting(RealtimeConsumer.Route::destination)
                .containsOnly(RealtimeGateway.SETTLEMENTS_DESTINATION);
    }

    @Test
    void privateSettlementChangesReachOnlyTheTwoPartiesWithoutAmounts() {
        List<RealtimeConsumer.Route> routes = routes(OutboxEvent.SETTLEMENT_NET_CHANGED,
                Map.of("userId", PAYER, "otherUserId", PAYEE, "beforeCents", 100, "afterCents", -25));
        assertThat(routes).extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
        for (RealtimeConsumer.Route route : routes) {
            assertThat(JSON.valueToTree(route.invalidation()).toString())
                    .doesNotContain("100").doesNotContain("-25").doesNotContain(PAYEE.toString());
        }
        assertThat(routes(OutboxEvent.SETTLEMENT_LINE_CHANGED,
                Map.of("lineId", UUID.randomUUID(), "payerId", PAYER, "payeeId", PAYEE)))
                .extracting(RealtimeConsumer.Route::userId).containsExactlyInAnyOrder(PAYER, PAYEE);
    }

    @Test
    void chatEventsInvalidateTheChatWithoutMessageIdentity() {
        UUID message = UUID.randomUUID();
        assertThat(routes(OutboxEvent.CHAT_MESSAGE_CREATED, Map.of("tableId", TABLE, "messageId", message, "kind", "text")))
                .singleElement().satisfies(route -> {
                    assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.CHAT);
                    assertThat(JSON.valueToTree(route.invalidation()).toString()).doesNotContain(message.toString());
                });
    }

    @Test
    void tableChangesInvalidateTheTableViewForMembers() {
        for (OutboxEvent type : List.of(OutboxEvent.TABLE_SETTINGS_CHANGED, OutboxEvent.TABLE_REMAINDER_CHANGED,
                OutboxEvent.TABLE_QUICKPICKS_CHANGED)) {
            assertThat(routes(type, Map.of("tableId", TABLE))).singleElement().satisfies(route -> {
                assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.VIEW);
                assertThat(route.onlyUsers()).isNull();
                assertThat(route.invalidation().type()).isEqualTo("table.changed");
            });
        }
        List<RealtimeConsumer.Route> host = routes(OutboxEvent.TABLE_HOST_CHANGED, Map.of("tableId", TABLE));
        assertThat(personal(host, "tables.changed")).containsExactlyInAnyOrder(HOST, MEMBER);
    }

    @Test
    void profileChangesAlsoReachTableListsAndPendingInvitees() {
        List<RealtimeConsumer.Route> routes = routes(OutboxEvent.TABLE_PROFILE_CHANGED, Map.of("tableId", TABLE));
        assertThat(routes).anySatisfy(route -> assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.VIEW));
        assertThat(personal(routes, "tables.changed")).containsExactlyInAnyOrder(HOST, MEMBER);
        assertThat(personal(routes, "invitations.changed")).containsExactly(INVITEE);
    }

    @Test
    void permissionChangesReachOnlyHostsAndTheAffectedMember() {
        UUID affected = UUID.randomUUID();
        assertThat(routes(OutboxEvent.TABLE_MEMBER_PERMISSION_CHANGED, Map.of("tableId", TABLE, "userId", affected)))
                .singleElement().satisfies(route -> {
                    assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.VIEW);
                    assertThat(route.onlyUsers()).containsExactlyInAnyOrder(HOST, affected);
                });
    }

    @Test
    void membershipChangesRefreshTheAffectedUsersTableList() {
        assertThat(personal(routes(OutboxEvent.TABLE_MEMBER_JOINED, Map.of("tableId", TABLE, "userId", INVITEE)),
                "tables.changed")).containsExactly(INVITEE);
        List<RealtimeConsumer.Route> removed = routes(OutboxEvent.TABLE_MEMBER_REMOVED,
                Map.of("tableId", TABLE, "removedUserId", FORMER));
        assertThat(personal(removed, "tables.changed")).containsExactly(FORMER);
        assertThat(removed).anySatisfy(route -> assertThat(route.tableFeed()).isEqualTo(RealtimeGateway.TableFeed.VIEW));
        assertThat(personal(routes(OutboxEvent.TABLE_ARCHIVED, Map.of("tableId", TABLE)), "tables.changed"))
                .containsExactlyInAnyOrder(HOST, FORMER);
        assertThat(personal(routes(OutboxEvent.TABLE_CREATED, Map.of("tableId", TABLE)), "tables.changed"))
                .containsExactlyInAnyOrder(HOST, MEMBER);
    }

    @Test
    void invitationsReachOnlyTheInvitee() {
        ObjectNode event = event(UUID.randomUUID(), OutboxEvent.TABLE_INVITATION_CREATED,
                Map.of("invitationId", UUID.randomUUID()));
        event.put("aggregateId", INVITEE.toString());
        List<RealtimeConsumer.Route> routes = RealtimeConsumer.routes(event, AUDIENCES);
        assertThat(personal(routes, "invitations.changed")).containsExactly(INVITEE);
        assertThat(routes).hasSize(1);
    }

    @Test
    void profileChangesNameOnlyTheUserAndReachTheirAudienceAndThemselves() {
        List<RealtimeConsumer.Route> routes = routes(OutboxEvent.USER_PROFILE_CHANGED, Map.of("userId", MEMBER));
        assertThat(personal(routes, "profile.changed")).containsExactlyInAnyOrder(HOST, MEMBER);
        assertThat(routes).allSatisfy(route -> assertThat(route.invalidation().resourceId()).isEqualTo(MEMBER));
    }

    @Test
    void everyInvalidationMatchesTheContract() {
        Map<OutboxEvent, Map<String, ?>> payloads = Map.ofEntries(
                Map.entry(OutboxEvent.BET_LOCKED, Map.of("tableId", TABLE, "betId", BET)),
                Map.entry(OutboxEvent.SETTLEMENT_CREATED, Map.of("tableId", TABLE, "settlementId", SETTLEMENT)),
                Map.entry(OutboxEvent.CHAT_REACTION_CHANGED, Map.of("tableId", TABLE)),
                Map.entry(OutboxEvent.TABLE_PROFILE_CHANGED, Map.of("tableId", TABLE)),
                Map.entry(OutboxEvent.TABLE_MEMBER_PERMISSION_CHANGED, Map.of("tableId", TABLE, "userId", MEMBER)),
                Map.entry(OutboxEvent.USER_PROFILE_CHANGED, Map.of("userId", MEMBER)));
        payloads.forEach((type, payload) -> routes(type, payload).forEach(route ->
                assertThat(RealtimeSchema.SCHEMA.validate(JSON.valueToTree(route.invalidation())))
                        .as(type.eventType()).isEmpty()));
    }

    @Test
    void unknownEventsAreIgnoredAndRedeliveryKeepsTheEventId() {
        ObjectNode unknown = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("eventType", "x.v1");
        assertThat(RealtimeConsumer.routes(unknown, AUDIENCES)).isEmpty();
        ObjectNode event = event(UUID.randomUUID(), OutboxEvent.BET_LOCKED, Map.of("tableId", TABLE, "betId", BET));
        assertThat(RealtimeConsumer.routes(event, AUDIENCES).getFirst().invalidation().eventId())
                .isEqualTo(RealtimeConsumer.routes(event, AUDIENCES).getFirst().invalidation().eventId());
    }

    private static List<RealtimeConsumer.Route> routes(OutboxEvent type, Map<String, ?> payload) {
        return RealtimeConsumer.routes(event(UUID.randomUUID(), type, payload), AUDIENCES);
    }

    private static List<UUID> personal(List<RealtimeConsumer.Route> routes, String type) {
        return routes.stream()
                .filter(route -> RealtimeGateway.PERSONAL_DESTINATION.equals(route.destination()))
                .filter(route -> route.invalidation().type().equals(type))
                .map(RealtimeConsumer.Route::userId)
                .toList();
    }

    private static ObjectNode event(UUID id, OutboxEvent type, Map<String, ?> payload) {
        ObjectNode event = JSON.createObjectNode().put("id", id.toString()).put("eventType", type.eventType())
                .put("createdAt", "2026-11-13T12:00:00Z");
        JsonNode body = JSON.valueToTree(payload);
        event.set("payload", body);
        return event;
    }
}
