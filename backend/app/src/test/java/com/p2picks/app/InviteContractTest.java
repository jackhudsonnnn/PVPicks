package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class InviteContractTest {

    @Test
    void pendingInviteIncludesTablePreviewWithoutFullTableAccess() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("TableInvite");
        ObjectNode invite = new ObjectMapper().createObjectNode();
        invite.put("id", "95586d98-8dae-455a-b642-51bd5c8cb8f8");
        invite.put("tableId", "1f006a5f-fd02-41ff-aee8-52b87d11d53e");
        invite.put("fromId", "52a73783-b3cf-438a-8174-ac235c54eb6e");
        invite.put("userId", "30aa2f85-cd90-41b7-859b-9a7a54280ae5");
        invite.put("createdAt", "2026-11-11T12:00:00Z");
        invite.put("tableName", "Friends");
        invite.put("tableEmoji", "\uD83C\uDFB2");
        assertThat(schema.validate(invite)).isEmpty();
        invite.remove("tableName");
        assertThat(schema.validate(invite)).isNotEmpty();
        invite.put("tableName", "Friends");
        invite.remove("tableEmoji");
        assertThat(schema.validate(invite)).isNotEmpty();
    }
}
