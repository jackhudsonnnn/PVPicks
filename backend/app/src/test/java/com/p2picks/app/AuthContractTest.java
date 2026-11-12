package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class AuthContractTest {

    @Test
    void csrfResponseRequiresOnlyANonemptyToken() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("CsrfToken");
        ObjectNode response = new ObjectMapper().createObjectNode().put("token", "opaque-token");
        assertThat(schema.validate(response)).isEmpty();
        response.put("token", "");
        assertThat(schema.validate(response)).isNotEmpty();
        response.remove("token");
        assertThat(schema.validate(response)).isNotEmpty();
        response.put("token", "opaque-token").put("session", "must-not-be-exposed");
        assertThat(schema.validate(response)).isNotEmpty();
    }
}
