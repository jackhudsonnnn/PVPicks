package com.p2picks.app.security;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/setup} redeems a setup or reset code (signed out, like login); {@code POST /auth/password}
 * changes your own password (any signed-in account). Failed attempts share the sign-in throttle.
 */
@RestController
class PasswordController {
    private final PasswordService passwords;
    private final LoginThrottle throttle;
    private final CsrfTokenRepository csrfTokens;
    private final SessionCookies cookies;

    PasswordController(PasswordService passwords, LoginThrottle throttle, CsrfTokenRepository csrfTokens,
            SessionCookies cookies) {
        this.cookies = cookies;
        this.passwords = passwords;
        this.throttle = throttle;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/auth/setup")
    ResponseEntity<?> setup(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        if (!input.isObject() || input.size() != 2 || !input.path("code").isTextual()
                || !input.path("password").isTextual()) {
            return error(400, "bad_request", "A code and a password are required.");
        }
        String address = request.getRemoteAddr();
        if (throttle.blockedAddress(address)) return rateLimited();
        Cookie[] cookies = request.getCookies();
        String previous = cookies == null ? null : Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals("session")).map(Cookie::getValue).findFirst().orElse(null);
        PasswordService.SetupResult result = passwords.redeem(input.path("code").textValue(),
                input.path("password").textValue(), previous);
        if (result instanceof PasswordService.SetupResult.InvalidCode) {
            throttle.failedAddress(address);
            return error(400, "invalid_setup_code",
                    "This setup link has expired or was already used. Ask the admin for a new one.");
        }
        if (result instanceof PasswordService.SetupResult.Rejected rejected) {
            return error(400, "weak_password", rejected.problem());
        }
        String token = ((PasswordService.SetupResult.SignedIn) result).token();
        response.addHeader(HttpHeaders.SET_COOKIE, this.cookies.session(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
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
