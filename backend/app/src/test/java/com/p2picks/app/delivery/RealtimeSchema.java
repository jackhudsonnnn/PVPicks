package com.p2picks.app.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/** The realtime invalidation contract (api/realtime.schema.json). */
final class RealtimeSchema {
    static final JsonSchema SCHEMA = load();

    private static JsonSchema load() {
        try (InputStream input = Objects.requireNonNull(
                RealtimeSchema.class.getResourceAsStream("/realtime.schema.json"))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(new ObjectMapper().readTree(input));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private RealtimeSchema() {}
}
