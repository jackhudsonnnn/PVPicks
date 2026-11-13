package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatSendController {
    private static final Set<String> GIF_IDS = Set.of(
            "g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8", "g9", "g10", "g11", "g12",
            "g13", "g14", "g15", "g16", "g17", "g18", "g19", "g20", "g21", "g22", "g23", "g24");

    private final ChatSendService chat;

    ChatSendController(ChatSendService chat) {
        this.chat = chat;
    }

    @PostMapping("/tables/{tableId}/messages")
    ResponseEntity<?> send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        ChatSendService.Submission submission = parse(input);
        if (submission == null) return error(400, "bad_request", "Invalid chat message.");
        try {
            ChatSendService.Result result = chat.send(jwt, tableId, key, submission);
            return switch (result.outcome()) {
                case CREATED, REPLAYED -> ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(result.message());
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case FORBIDDEN -> error(403, "forbidden", "Chat permission is disabled.");
                case NOT_FOUND -> error(404, "not_found", "Table or message not found.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ChatSendService.Submission parse(JsonNode input) {
        if (!input.isObject() || input.size() != 2 || !input.path("kind").isTextual()) return null;
        String kind = input.path("kind").textValue();
        if (kind.equals("text") && input.path("text").isTextual()) {
            String text = PostgresText.trim(input.path("text").textValue());
            if (!text.isEmpty() && PostgresText.valid(text) && text.codePointCount(0, text.length()) <= 2000) {
                return new ChatSendService.Submission(kind, text);
            }
        } else if (kind.equals("gif") && input.path("gifId").isTextual()
                && GIF_IDS.contains(input.path("gifId").textValue())) {
            return new ChatSendService.Submission(kind, input.path("gifId").textValue());
        }
        return null;
    }
}
