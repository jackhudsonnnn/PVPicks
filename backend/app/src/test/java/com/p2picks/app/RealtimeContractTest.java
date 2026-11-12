package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RealtimeContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonSchema schema;

    @BeforeAll
    static void loadContract() throws IOException {
        try (InputStream input =
                Objects.requireNonNull(RealtimeContractTest.class.getResourceAsStream("/realtime.schema.json"))) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(JSON.readTree(input));
        }
    }

    @Test
    void chatInvalidationCannotIncludeMessageIdentityOrContent() {
        ObjectNode event = event("chat.changed");
        event.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        assertThat(schema.validate(event)).isEmpty();
        event.put("resourceId", "95586d98-8dae-455a-b642-51bd5c8cb8f8");
        assertThat(schema.validate(event)).isNotEmpty();
        event.remove("resourceId");
        event.put("text", "hidden message");
        assertThat(schema.validate(event)).isNotEmpty();
    }

    @Test
    void betInvalidationRequiresTableAndBetIdentityButNoPick() {
        ObjectNode event = event("bet.changed");
        event.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        assertThat(schema.validate(event)).isNotEmpty();
        event.put("resourceId", "95586d98-8dae-455a-b642-51bd5c8cb8f8");
        assertThat(schema.validate(event)).isEmpty();
        event.put("myPick", "Home");
        assertThat(schema.validate(event)).isNotEmpty();
    }

    @Test
    void userAndTickerInvalidationsCarryNoPrivateResourceIds() {
        for (String type : new String[] {"notifications.changed", "ticker.changed"}) {
            ObjectNode event = event(type);
            assertThat(schema.validate(event)).as(type).isEmpty();
            event.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
            assertThat(schema.validate(event)).as(type).isNotEmpty();
            event.remove("tableId");
            event.put("resourceId", "95586d98-8dae-455a-b642-51bd5c8cb8f8");
            assertThat(schema.validate(event)).as(type).isNotEmpty();
        }
    }

    private static ObjectNode event(String type) {
        ObjectNode event = JSON.createObjectNode();
        event.put("eventId", "a9b04155-1c73-4c69-b020-000000000001");
        event.put("type", type);
        event.put("schemaVersion", 1);
        event.put("occurredAt", "2026-11-11T12:00:00Z");
        return event;
    }
}
