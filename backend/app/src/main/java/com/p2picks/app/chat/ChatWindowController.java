package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatWindowController {
    private final ChatWindowService windows;
    private final ChatCursor cursors;

    ChatWindowController(ChatWindowService windows, ChatCursor cursors) {
        this.windows = windows;
        this.cursors = cursors;
    }

    @GetMapping("/chat/{tableId}/messages")
    ResponseEntity<?> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID tableId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (parameters.size() > 1 || parameters.values().stream().anyMatch(values -> values.size() != 1)
                || parameters.keySet().stream().anyMatch(key -> !List.of("before", "after", "around").contains(key))) {
            return error(400, "bad_request", "Provide at most one valid chat cursor.");
        }
        ChatWindowService.Query query;
        try {
            if (parameters.containsKey("around")) {
                String value = parameters.getFirst("around");
                UUID anchor = UUID.fromString(value);
                if (!anchor.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID");
                query = new ChatWindowService.Query(ChatWindowService.Direction.AROUND, null, anchor);
            } else if (parameters.containsKey("before") || parameters.containsKey("after")) {
                boolean before = parameters.containsKey("before");
                String token = parameters.getFirst(before ? "before" : "after");
                long position = cursors.decode(token, UUID.fromString(jwt.getSubject()), tableId);
                query = new ChatWindowService.Query(before ? ChatWindowService.Direction.BEFORE
                        : ChatWindowService.Direction.AFTER, position, null);
            } else {
                query = new ChatWindowService.Query(ChatWindowService.Direction.LATEST, null, null);
            }
        } catch (ChatCursor.InvalidCursor | IllegalArgumentException exception) {
            return error(400, "bad_request", "Provide at most one valid chat cursor.");
        }
        ChatWindowService.Result result = windows.read(jwt, tableId, query);
        if (!result.authenticated()) return error(401, "unauthorized", "Authentication required or credentials invalid.");
        if (!result.accessible() || !result.anchorFound()) return error(404, "not_found", "Table or message not found.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.window());
    }
}
