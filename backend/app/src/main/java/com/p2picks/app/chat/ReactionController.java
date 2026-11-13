package com.p2picks.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
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
class ReactionController {
    private final ReactionService reactions;

    ReactionController(ReactionService reactions) {
        this.reactions = reactions;
    }

    @PutMapping("/messages/{messageId}/reaction")
    ResponseEntity<?> put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (!input.isObject() || input.size() != 1 || !input.path("emoji").isTextual()
                || !UCharacter.hasBinaryProperty(input.path("emoji").textValue(), UProperty.RGI_EMOJI)) {
            return error(400, "bad_request", "Reaction must be one supported emoji.");
        }
        return change(jwt, messageId, input.path("emoji").textValue(), key);
    }

    @DeleteMapping("/messages/{messageId}/reaction")
    ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID messageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (body != null && body.length != 0) {
            return error(400, "bad_request", "This operation does not accept a request body.");
        }
        return change(jwt, messageId, null, key);
    }

    private ResponseEntity<?> change(Jwt jwt, UUID messageId, String emoji, String key) {
        try {
            return switch (reactions.change(jwt, messageId, emoji, key)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "React permission is disabled.");
                case NOT_FOUND -> error(404, "not_found", "Message not found.");
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
