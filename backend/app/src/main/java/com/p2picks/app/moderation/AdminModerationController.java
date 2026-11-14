package com.p2picks.app.moderation;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.security.MutationAuthentication;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin moderation queue and people list. `/admin/**` is admin-only (SessionSecurity). */
@RestController
class AdminModerationController {
    private final ModerationService moderation;

    AdminModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/admin/reports")
    ResponseEntity<?> reports(@RequestParam(defaultValue = "open") String status) {
        if (!status.equals("open") && !status.equals("all")) return error(400, "bad_request", "status is open or all.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.reports(status.equals("all")));
    }

    @GetMapping("/admin/users/flagged")
    ResponseEntity<?> flagged() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.flagged());
    }

    @PostMapping("/admin/reports/{reportId}/dismiss")
    ResponseEntity<?> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(key, () -> moderation.dismiss(jwt, reportId, key), null);
    }

    @PostMapping("/admin/reports/{reportId}/strike")
    ResponseEntity<?> strike(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        ModerationService.StrikeResult[] result = new ModerationService.StrikeResult[1];
        return respond(key, () -> {
            result[0] = moderation.strike(jwt, reportId, key);
            return result[0].outcome();
        }, () -> Map.of("strikes", result[0].strikes(), "frozen", result[0].frozen()));
    }

    @PostMapping("/admin/users/{userId}/unfreeze")
    ResponseEntity<?> unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(key, () -> moderation.unfreeze(jwt, userId, key), null);
    }

    @PostMapping("/admin/users/{userId}/clear-strikes")
    ResponseEntity<?> clearStrikes(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return respond(key, () -> moderation.clearStrikes(jwt, userId, key), null);
    }

    private ResponseEntity<?> respond(String key, Supplier<ModerationService.Outcome> action, Supplier<Object> body) {
        if (key == null || key.isBlank()) return error(400, "bad_request", "A valid Idempotency-Key is required.");
        try {
            return switch (action.get()) {
                case UPDATED -> body == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                        : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body.get());
                case REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case NOT_FOUND -> error(404, "not_found", "Not found.");
                case ALREADY_HANDLED -> error(409, "already_handled", "Someone already handled this.");
            };
        } catch (IllegalArgumentException invalid) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}
