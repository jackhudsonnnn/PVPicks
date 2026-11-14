package com.p2picks.app.tables;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TomatoController {
    private final TomatoService tomatoes;

    TomatoController(TomatoService tomatoes) {
        this.tomatoes = tomatoes;
    }

    @PostMapping("/tables/{tableId}/tomatoes")
    ResponseEntity<?> throwAt(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        UUID target;
        try {
            if (!input.isObject() || input.size() != 1 || !input.path("targetId").isTextual()) {
                return error(400, "bad_request", "Invalid tomato.");
            }
            target = UUID.fromString(input.path("targetId").textValue());
        } catch (IllegalArgumentException invalid) {
            return error(400, "bad_request", "Invalid tomato.");
        }
        try {
            return switch (tomatoes.throwAt(jwt, tableId, target, key)) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
                case FORBIDDEN -> error(403, "forbidden", "A host turned off reactions for you at this table.");
                case NOT_FOUND -> error(404, "not_found", "Table or person not found.");
                case RATE_LIMITED -> rateLimited();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (TomatoService.CoolingDown cooling) {
            return rateLimited();
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    @GetMapping("/tables/{tableId}/tomatoes")
    ResponseEntity<?> recent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        TomatoService.Read read = tomatoes.recent(jwt, tableId);
        if (!read.authenticated()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        if (read.throwsSeen() == null) return error(404, "not_found", "Table not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read.throwsSeen().stream()
                .map(t -> Map.of("id", t.id(), "throwerId", t.throwerId(), "targetId", t.targetId(),
                        "thrownAt", t.thrownAt().toString()))
                .toList());
    }

    private static ResponseEntity<Map<String, String>> rateLimited() {
        // Same error body as every other endpoint, plus when to try again.
        return ResponseEntity.status(429).header("Retry-After", String.valueOf(TomatoService.COOLDOWN_SECONDS))
                .body(error(429, "rate_limited", "One tomato every few seconds.").getBody());
    }
}
