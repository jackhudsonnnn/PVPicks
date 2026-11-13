package com.p2picks.app.tables;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableQuickPickController {
    private final TableQuickPickService quickPicks;

    TableQuickPickController(TableQuickPickService quickPicks) {
        this.quickPicks = quickPicks;
    }

    @PutMapping("/tables/{tableId}/quick-picks")
    ResponseEntity<?> enabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 1 || !input.path("enabled").isBoolean()) {
            return error(400, "bad_request", "Invalid Quick Pick settings.");
        }
        return change(jwt, tableId, key, input.path("enabled").booleanValue()
                ? TableQuickPickService.Action.ENABLE : TableQuickPickService.Action.DISABLE);
    }

    @PostMapping("/tables/{tableId}/quick-picks/share")
    ResponseEntity<?> share(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return withoutBody(jwt, tableId, key, body, TableQuickPickService.Action.SHARE);
    }

    @DeleteMapping("/tables/{tableId}/quick-picks/share")
    ResponseEntity<?> stopSharing(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return withoutBody(jwt, tableId, key, body, TableQuickPickService.Action.STOP_SHARING);
    }

    @PostMapping("/tables/{tableId}/quick-picks/rotate")
    ResponseEntity<?> rotate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return withoutBody(jwt, tableId, key, body, TableQuickPickService.Action.ROTATE);
    }

    private ResponseEntity<?> withoutBody(Jwt jwt, UUID tableId, String key, byte[] body,
            TableQuickPickService.Action action) {
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a request body.");
        return change(jwt, tableId, key, action);
    }

    private ResponseEntity<?> change(Jwt jwt, UUID tableId, String key, TableQuickPickService.Action action) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        try {
            TableQuickPickService.Result result = quickPicks.change(jwt, tableId, action, key);
            return switch (result.outcome()) {
                case UPDATED, REPLAYED -> action == TableQuickPickService.Action.ROTATE
                        ? ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("code", result.code()))
                        : ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change Quick Pick settings.");
                case NOT_FOUND -> error(404, "not_found", "Table not found.");
            };
        } catch (TableQuickPickService.FeatureDisabled exception) {
            return error(409, "conflict", "Quick Picks must be enabled before sharing.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
