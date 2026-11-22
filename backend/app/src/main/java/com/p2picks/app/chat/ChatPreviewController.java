package com.p2picks.app.chat;

import static com.p2picks.app.common.ApiErrors.error;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatPreviewController {
    private final ChatPreviewService previews;

    ChatPreviewController(ChatPreviewService previews) {
        this.previews = previews;
    }

    @GetMapping("/me/chat/latest")
    ResponseEntity<?> latest(@AuthenticationPrincipal Jwt jwt) {
        return previews.latest(jwt)
                .<ResponseEntity<?>>map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> error(401, "unauthorized", "Authentication required or credentials invalid."));
    }
}
