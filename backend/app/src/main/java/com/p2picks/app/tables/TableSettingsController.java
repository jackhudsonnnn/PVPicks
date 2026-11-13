package com.p2picks.app.tables;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
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
class TableSettingsController {
    private final TableSettingsService settings;
    private final TableReadController tableReads;

    TableSettingsController(TableSettingsService settings, TableReadController tableReads) {
        this.settings = settings;
        this.tableReads = tableReads;
    }

    @PatchMapping("/tables/{tableId}/settings")
    ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.isEmpty() || input.size() > 2
                || input.has("hostsOnlyBets") && !input.get("hostsOnlyBets").isBoolean()
                || input.has("u2pickEnabled") && !input.get("u2pickEnabled").isBoolean()
                || input.size() != (input.has("hostsOnlyBets") ? 1 : 0) + (input.has("u2pickEnabled") ? 1 : 0)) {
            return error(400, "bad_request", "Invalid table settings.");
        }
        var changes = new TableSettingsService.Changes(
                input.has("hostsOnlyBets") ? input.get("hostsOnlyBets").booleanValue() : null,
                input.has("u2pickEnabled") ? input.get("u2pickEnabled").booleanValue() : null);
        try {
            return switch (settings.update(jwt, tableId, changes, key)) {
                case UPDATED, REPLAYED -> tableReads.get(jwt, tableId);
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change table settings.");
                case NOT_FOUND -> error(404, "not_found", "Table not found.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}
