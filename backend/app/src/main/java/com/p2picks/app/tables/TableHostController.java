package com.p2picks.app.tables;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableHostController {
    private final TableHostService hosts;

    TableHostController(TableHostService hosts) {
        this.hosts = hosts;
    }

    @PutMapping("/tables/{tableId}/hosts/{userId}")
    ResponseEntity<?> promote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return set(jwt, tableId, userId, key, body, true);
    }

    @DeleteMapping("/tables/{tableId}/hosts/{userId}")
    ResponseEntity<?> demote(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return set(jwt, tableId, userId, key, body, false);
    }

    private ResponseEntity<?> set(Jwt jwt, UUID tableId, UUID target, String key, byte[] body, boolean host) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (body != null && body.length != 0) return error(400, "bad_request", "This operation does not accept a request body.");
        try {
            return switch (hosts.set(jwt, tableId, target, host, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change hosts.");
                case NOT_FOUND -> error(404, "not_found", "Table or member not found.");
            };
        } catch (TableHostService.LastActiveHost exception) {
            return error(409, "conflict", "A table must retain an active host.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
