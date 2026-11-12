package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class AdminActionContractTest {

    @Test
    void actionVariantsRequireOnlyTheirOwnFields() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("AdminUserAction");
        ObjectMapper json = new ObjectMapper();
        ObjectNode action = json.createObjectNode().put("type", "freeze").put("on", true);
        assertThat(schema.validate(action)).isEmpty();
        action.put("type", "flag").put("on", false);
        assertThat(schema.validate(action)).isEmpty();
        action.remove("on");
        assertThat(schema.validate(action)).isNotEmpty();
        action.put("type", "strike");
        assertThat(schema.validate(action)).isEmpty();
        action.put("on", true);
        assertThat(schema.validate(action)).isNotEmpty();
        action.remove("on");
        action.put("type", "clearStrikes");
        assertThat(schema.validate(action)).isEmpty();
        action.put("type", "note").put("text", "  Review account  ");
        assertThat(schema.validate(action)).isEmpty();
        action.put("text", " \t\n ");
        assertThat(schema.validate(action)).isNotEmpty();
        action.put("type", "unknown");
        assertThat(schema.validate(action)).isNotEmpty();
    }
}
