package com.p2picks.app.tables;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableProfileController {
    private final TableProfileService profiles;
    private final TableReadController tableReads;

    TableProfileController(TableProfileService profiles, TableReadController tableReads) {
        this.profiles = profiles;
        this.tableReads = tableReads;
    }

    @PatchMapping("/tables/{tableId}")
    ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.isEmpty() || input.size() > 2
                || input.has("name") && !input.get("name").isTextual()
                || input.has("emoji") && !input.get("emoji").isTextual()
                || input.size() != (input.has("name") ? 1 : 0) + (input.has("emoji") ? 1 : 0)) {
            return error(400, "bad_request", "Invalid table details.");
        }
        String name = input.has("name") ? PostgresText.trim(input.get("name").textValue()) : null;
        String emoji = input.has("emoji") ? input.get("emoji").textValue() : null;
        if (name != null && !TableProfileValidation.validName(name)
                || emoji != null && !TableProfileValidation.validEmoji(emoji)) {
            return error(400, "bad_request", "Invalid table details.");
        }
        try {
            return switch (profiles.update(jwt, tableId, new TableProfileService.Changes(name, emoji), key)) {
                case UPDATED, REPLAYED -> tableReads.get(jwt, tableId);
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change table details.");
                case NOT_FOUND -> error(404, "not_found", "Table not found.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}
