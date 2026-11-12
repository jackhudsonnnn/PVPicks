package com.p2picks.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class FeedbackContractTest {

    @Test
    void acceptsOptionalTrimmedOrEmptyLinksButRejectsNonHttpLinks() throws IOException {
        JsonSchema schema = OpenApiSchemas.load("FeedbackSubmission");
        ObjectNode feedback = new ObjectMapper().createObjectNode();
        feedback.put("kind", "gif");
        feedback.put("text", "a good gif idea");
        assertThat(schema.validate(feedback)).isEmpty();
        feedback.put("link", "  HTTPS://example.com/gif  ");
        assertThat(schema.validate(feedback)).isEmpty();
        feedback.put("link", "  ");
        assertThat(schema.validate(feedback)).isEmpty();
        feedback.put("link", "tenor dot com");
        assertThat(schema.validate(feedback)).isNotEmpty();
        feedback.put("link", "https://");
        assertThat(schema.validate(feedback)).isNotEmpty();
    }
}
