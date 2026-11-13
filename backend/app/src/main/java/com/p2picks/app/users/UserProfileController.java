package com.p2picks.app.users;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserProfileController {
    private final UserProfileService profiles;

    UserProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @PatchMapping("/me/profile")
    ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        if (!input.isObject() || input.isEmpty() || input.size() > 2
                || input.has("username") && !input.get("username").isTextual()
                || input.has("hue") && (!input.get("hue").isIntegralNumber() || !input.get("hue").canConvertToInt())
                || input.size() != (input.has("username") ? 1 : 0) + (input.has("hue") ? 1 : 0)) {
            return error(400, "bad_request", "Invalid profile.");
        }
        String username = input.has("username") ? PostgresText.trim(input.get("username").textValue())
                .toLowerCase(Locale.ROOT) : null;
        Integer hue = input.has("hue") ? input.get("hue").intValue() : null;
        if (username != null && !username.matches("[a-z0-9_]{3,15}")
                || hue != null && (hue < 1 || hue > 5)) {
            return error(400, "bad_request", "Invalid profile.");
        }
        try {
            var result = profiles.update(jwt, new UserProfileService.Changes(username, hue), key);
            return switch (result.outcome()) {
                case UPDATED, REPLAYED -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.profile());
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (UserProfileService.UsernameTaken exception) {
            return error(409, "username_taken", "That username is already in use.");
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("code", code, "message", message));
    }
}
