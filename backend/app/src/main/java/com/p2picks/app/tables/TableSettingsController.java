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

    private static final java.util.List<String> SETTINGS = java.util.List.of("hostsOnlyBets", "u2pickEnabled",
            "pollsEnabled", "blackjackAccess");
    private static final java.util.Set<String> BLACKJACK_ACCESS = java.util.Set.of("hosts", "everyone", "off");

    private static Boolean flag(JsonNode input, String name) {
        return input.has(name) ? input.get(name).booleanValue() : null;
    }

    @PatchMapping("/tables/{tableId}/settings")
    ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.isEmpty()
                || input.size() != SETTINGS.stream().filter(input::has).count()
                || SETTINGS.stream().anyMatch(name -> !name.equals("blackjackAccess") && input.has(name)
                        && !input.get(name).isBoolean())
                || (input.has("blackjackAccess") && !BLACKJACK_ACCESS.contains(input.get("blackjackAccess").asText("")))) {
            return error(400, "bad_request", "Invalid table settings.");
        }
        var changes = new TableSettingsService.Changes(flag(input, "hostsOnlyBets"), flag(input, "u2pickEnabled"),
                flag(input, "pollsEnabled"),
                input.has("blackjackAccess") ? input.get("blackjackAccess").textValue() : null);
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
