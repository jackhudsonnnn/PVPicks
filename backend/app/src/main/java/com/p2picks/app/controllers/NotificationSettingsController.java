package com.p2picks.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationSettingsController {
    private static final Set<String> FIELDS =
            Set.of("enabled", "errors", "ticketStatus", "ticketScope", "chats", "invites", "mutedTableIds");
    private final NotificationSettingsService settings;

    NotificationSettingsController(NotificationSettingsService settings) {
        this.settings = settings;
    }

    @PutMapping("/me/notification-preferences")
    ResponseEntity<?> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        NotificationSettingsService.Preferences preferences = parse(input);
        if (preferences == null) {
            return error(400, "bad_request", "Invalid notification preferences.");
        }
        try {
            return switch (settings.replace(jwt, key, preferences)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (NotificationSettingsService.InvalidMute exception) {
            return error(404, "not_found", "Muted table not found.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static NotificationSettingsService.Preferences parse(JsonNode input) {
        if (!input.isObject() || input.size() != FIELDS.size()
                || !FIELDS.stream().allMatch(input::has)
                || !input.path("enabled").isBoolean() || !input.path("errors").isBoolean()
                || !input.path("ticketStatus").isBoolean() || !input.path("ticketScope").isTextual()
                || !input.path("chats").isBoolean() || !input.path("invites").isBoolean()
                || !input.path("mutedTableIds").isArray()) {
            return null;
        }
        String scope = input.path("ticketScope").textValue();
        if (!scope.equals("mine") && !scope.equals("all")) {
            return null;
        }
        List<UUID> tableIds = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode node : input.path("mutedTableIds")) {
            if (!node.isTextual()) return null;
            UUID id;
            try {
                id = UUID.fromString(node.textValue());
            } catch (IllegalArgumentException exception) {
                return null;
            }
            if (!id.toString().equalsIgnoreCase(node.textValue()) || !seen.add(id)) return null;
            tableIds.add(id);
        }
        return new NotificationSettingsService.Preferences(
                input.path("enabled").booleanValue(), input.path("errors").booleanValue(),
                input.path("ticketStatus").booleanValue(), scope, input.path("chats").booleanValue(),
                input.path("invites").booleanValue(), tableIds);
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
