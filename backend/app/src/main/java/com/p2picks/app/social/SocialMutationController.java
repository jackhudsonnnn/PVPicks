package com.p2picks.app.social;

import static com.p2picks.app.common.ApiErrors.error;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SocialMutationController {
    private final SocialMutationService social;

    SocialMutationController(SocialMutationService social) {
        this.social = social;
    }

    @PostMapping("/follows/{userId}")
    ResponseEntity<?> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, userId, key, body, SocialMutationService.Operation.FOLLOW);
    }

    @DeleteMapping("/follows/{userId}")
    ResponseEntity<?> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, userId, key, body, SocialMutationService.Operation.UNFOLLOW);
    }

    @PostMapping("/blocks/{userId}")
    ResponseEntity<?> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, userId, key, body, SocialMutationService.Operation.BLOCK);
    }

    @DeleteMapping("/blocks/{userId}")
    ResponseEntity<?> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) byte[] body) {
        return mutate(jwt, userId, key, body, SocialMutationService.Operation.UNBLOCK);
    }

    private ResponseEntity<?> mutate(
            Jwt jwt, UUID target, String key, byte[] body, SocialMutationService.Operation operation) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (body != null && body.length != 0) {
            return error(400, "bad_request", "This operation does not accept a request body.");
        }
        try {
            return switch (social.mutate(jwt, target, key, operation)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
                case NOT_FOUND -> error(404, "not_found", "User not found.");
                case BAD_REQUEST -> error(400, "bad_request", "Cannot change a relationship with yourself.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }
}
