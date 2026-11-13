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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TablePermissionController {
    private final TablePermissionService permissions;

    TablePermissionController(TablePermissionService permissions) {
        this.permissions = permissions;
    }

    @PutMapping("/tables/{tableId}/members/{userId}/permissions")
    ResponseEntity<?> set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 2 || !input.path("permission").isTextual()
                || !input.path("enabled").isBoolean()) {
            return error(400, "bad_request", "Invalid member permission.");
        }
        TablePermissionService.Permission permission = TablePermissionService.Permission.parse(input.path("permission").textValue());
        if (permission == null) return error(400, "bad_request", "Invalid member permission.");
        try {
            return switch (permissions.set(jwt, tableId, userId, permission, input.path("enabled").booleanValue(), key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case HOST_TARGET -> error(409, "conflict", "A host's permissions cannot be limited.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change member permissions.");
                case NOT_FOUND -> error(404, "not_found", "Table or member not found.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
