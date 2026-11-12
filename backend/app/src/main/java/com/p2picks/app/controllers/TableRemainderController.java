package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TableRemainderController {
    private final TableRemainderService remainder;

    TableRemainderController(TableRemainderService remainder) {
        this.remainder = remainder;
    }

    @PatchMapping("/tables/{tableId}/remainder")
    ResponseEntity<?> set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 1 || !input.path("rule").isTextual()) {
            return error(400, "bad_request", "Invalid remainder rule.");
        }
        TableRemainderService.Rule rule = TableRemainderService.Rule.parse(input.path("rule").textValue());
        if (rule == null) return error(400, "bad_request", "Invalid remainder rule.");
        try {
            return switch (remainder.set(jwt, tableId, rule, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Only a host can change the remainder rule.");
                case NOT_FOUND -> error(404, "not_found", "Table not found.");
            };
        } catch (TableRemainderService.ActiveBet exception) {
            return error(409, "conflict", "The remainder rule cannot change while a bet is active.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
