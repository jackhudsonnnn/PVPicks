package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class OpenApiSchemas {

    public static JsonSchema load(String name) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream input =
                Objects.requireNonNull(OpenApiSchemas.class.getResourceAsStream("/openapi.yaml"))) {
            JsonNode contract = yaml.readTree(input);
            ObjectNode schema = new ObjectMapper().createObjectNode();
            schema.put("$ref", "#/components/schemas/" + name);
            schema.set("components", contract.path("components"));
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        }
    }

    private OpenApiSchemas() {}
}
