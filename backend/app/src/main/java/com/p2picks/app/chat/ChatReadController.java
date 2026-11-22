package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatReadController {
    private final ChatReadService chat;

    ChatReadController(ChatReadService chat) {
        this.chat = chat;
    }

    @GetMapping("/tables/{tableId}/last-read")
    ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId) {
        ChatReadService.ReadResult result = chat.read(jwt, tableId);
        if (!result.authenticated()) return error(401, "unauthorized", "Authentication required or credentials invalid.");
        if (!result.accessible()) return error(404, "not_found", "Table not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.state());
    }

    @PutMapping("/tables/{tableId}/last-read")
    ResponseEntity<?> advance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.size() != 1 || !input.path("messageId").isTextual()) {
            return error(400, "bad_request", "A valid messageId is required.");
        }
        UUID messageId;
        try {
            String value = input.path("messageId").textValue();
            messageId = UUID.fromString(value);
            if (!messageId.toString().equalsIgnoreCase(value)) {
                return error(400, "bad_request", "A valid messageId is required.");
            }
        } catch (IllegalArgumentException exception) {
            return error(400, "bad_request", "A valid messageId is required.");
        }
        try {
            return switch (chat.advance(jwt, tableId, messageId, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case NOT_FOUND -> error(404, "not_found", "Table or message not found.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}
