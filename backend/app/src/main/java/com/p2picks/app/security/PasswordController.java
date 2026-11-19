package com.p2picks.app.security;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/password} changes your own password. Only admins have one (players sign in with a code), so a
 * player gets 403 {@code not_admin}. Wrong current passwords share the sign-in throttle.
 */
@RestController
class PasswordController {
    private final PasswordService passwords;
    private final LoginThrottle throttle;
    PasswordController(PasswordService passwords, LoginThrottle throttle) {
        this.passwords = passwords;
        this.throttle = throttle;
    }

    @PostMapping("/auth/password")
    ResponseEntity<?> change(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode input,
            HttpServletRequest request) {
        if (!input.isObject() || input.size() != 2 || !input.path("currentPassword").isTextual()
                || !input.path("newPassword").isTextual()) {
            return error(400, "bad_request", "The current and new passwords are required.");
        }
        String account = jwt.getSubject();
        String address = request.getRemoteAddr();
        if (throttle.blocked(account, address)) return rateLimited();
        Optional<String> problem = passwords.problem(jwt, input.path("newPassword").textValue());
        if (problem.isPresent()) return error(400, "weak_password", problem.get());
        try {
            return switch (passwords.change(jwt, input.path("currentPassword").textValue(),
                    input.path("newPassword").textValue())) {
                case CHANGED -> {
                    throttle.succeeded(account);
                    yield ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                }
                case WRONG_PASSWORD -> {
                    throttle.failed(account, address);
                    yield error(403, "wrong_password", "Your current password isn't right.");
                }
                case NOT_ADMIN -> error(403, "not_admin", "Players sign in with a code and have no password.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (MutationAuthentication.ExpiredSession expired) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }

    private ResponseEntity<Map<String, String>> rateLimited() {
        return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, Long.toString(throttle.window().toSeconds()))
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", "rate_limited", "message", "Too many failed attempts. Try again later."));
    }
}
