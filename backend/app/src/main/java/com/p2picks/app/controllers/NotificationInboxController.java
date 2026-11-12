package com.p2picks.app;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationInboxController {
    private final NotificationInboxService inbox;

    NotificationInboxController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @PostMapping("/notifications/read-all")
    ResponseEntity<?> markRead(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, key, body, false);
    }

    @DeleteMapping("/notifications")
    ResponseEntity<?> clear(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, key, body, true);
    }

    private ResponseEntity<?> mutate(Jwt jwt, String key, byte[] body, boolean clear) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (body != null && body.length != 0) {
            return error(400, "bad_request", "This operation does not accept a request body.");
        }
        try {
            return switch (clear ? inbox.clear(jwt, key) : inbox.markRead(jwt, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
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
