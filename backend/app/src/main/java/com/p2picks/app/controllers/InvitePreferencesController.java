package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvitePreferencesController {
    private static final Set<String> MODES = Set.of("open", "request", "closed");
    private final InvitePreferencesService settings;

    InvitePreferencesController(InvitePreferencesService settings) {
        this.settings = settings;
    }

    @PutMapping("/me/invite-preferences")
    ResponseEntity<?> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (!input.isObject() || input.size() != 2 || !input.path("friends").isTextual()
                || !input.path("following").isTextual()
                || !MODES.contains(input.path("friends").textValue())
                || !MODES.contains(input.path("following").textValue())) {
            return error(400, "bad_request", "Invalid invitation preferences.");
        }
        var preferences = new InvitePreferencesService.Preferences(
                input.path("friends").textValue(), input.path("following").textValue());
        try {
            return switch (settings.replace(jwt, key, preferences)) {
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
