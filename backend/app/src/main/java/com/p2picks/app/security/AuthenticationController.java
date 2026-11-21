package com.p2picks.app.security;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthenticationController {
    private final Sessions sessions;
    private final CsrfTokenRepository csrfTokens;
    private final JdbcTemplate jdbc;
    private final LoginThrottle throttle;
    private final SessionCookies cookies;
    private final SignInCodes codes;

    AuthenticationController(Sessions sessions, CsrfTokenRepository csrfTokens, JdbcTemplate jdbc,
            LoginThrottle throttle, SessionCookies cookies, SignInCodes codes) {
        this.codes = codes;
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.jdbc = jdbc;
        this.throttle = throttle;
        this.cookies = cookies;
    }

    @GetMapping("/auth/csrf")
    ResponseEntity<Map<String, String>> csrf(CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token", csrf.getToken()));
    }

    @PostMapping("/auth/login")
    ResponseEntity<?> login(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        if (!input.isObject() || input.size() != 2
                || !input.path("username").isTextual() || !input.path("password").isTextual()) {
            return error(400, "bad_request", "Username and password are required.");
        }
        Cookie[] cookies = request.getCookies();
        String previousToken = cookies == null ? null : Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals("session"))
                .map(Cookie::getValue).findFirst().orElse(null);
        String username = input.path("username").textValue();
        String address = request.getRemoteAddr();
        if (throttle.blocked(username, address)) {
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,
                            Long.toString(throttle.window().toSeconds()))
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "rate_limited",
                            "message", "Too many failed sign-in attempts. Try again later."));
        }
        String token;
        try {
            token = sessions.login(username, input.path("password").textValue(), previousToken);
        } catch (Sessions.Frozen frozen) {
            // The password was right; a frozen account is not a guessing attempt.
            return error(403, "account_frozen", "This account is frozen.");
        }
        if (token == null) {
            throttle.failed(username, address);
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        throttle.succeeded(username);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * A player signs in with their code, typed or opened from their QR link ({@code /signin#CODE}). Wrong codes
     * count against the address ({@link LoginThrottle#blockedCode}); a frozen account answers 403 only for a right
     * code.
     */
    @PostMapping("/auth/code")
    ResponseEntity<?> code(@RequestBody JsonNode input, HttpServletRequest request, HttpServletResponse response) {
        // Typed: {username, code}. A QR link: {player, code}, the player's id, so a rename doesn't break the photo.
        boolean typed = input.path("username").isTextual();
        boolean scanned = input.path("player").isTextual();
        if (!input.isObject() || input.size() != 2 || !input.path("code").isTextual() || typed == scanned) {
            return error(400, "bad_request", "A username (or player) and a sign-in code are required.");
        }
        UUID player = typed ? codes.playerNamed(input.path("username").textValue()).orElse(null)
                : parseId(input.path("player").textValue());
        LoginThrottle.CodeSubject subject = player != null ? LoginThrottle.CodeSubject.of(player)
                : LoginThrottle.CodeSubject.named(typed ? input.path("username").textValue() : "");
        String address = request.getRemoteAddr();
        if (throttle.blockedCode(subject, address)) {
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER,
                            Long.toString(throttle.window().toSeconds()))
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "rate_limited",
                            "message", "Too many wrong codes. Wait a few minutes, or ask the admin to unlock you."));
        }
        // An unknown username and a wrong code get the same answer, so sign-in doesn't reveal who exists.
        if (player == null || !codes.matches(player, input.path("code").textValue())) {
            throttle.failedCode(subject, address);
            return error(401, "invalid_code", "That username and code don't match. Check them with the admin.");
        }
        Cookie[] cookies = request.getCookies();
        String previousToken = cookies == null ? null : Arrays.stream(cookies)
                .filter(cookie -> cookie.getName().equals("session"))
                .map(Cookie::getValue).findFirst().orElse(null);
        String token;
        try {
            token = sessions.signInWithoutPassword(player, previousToken);
        } catch (Sessions.Frozen frozen) {
            return error(403, "account_frozen", "This account is frozen.");
        }
        if (token == null) return error(401, "invalid_code", "That username and code don't match. Check them with the admin.");
        throttle.codeSucceeded(player);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(UUID.fromString(jwt.getId()));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO));
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        var users = jdbc.query("""
                SELECT id, username::text, hue, role FROM users WHERE id = ? AND status = 'ACTIVE'
                """, (rs, row) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "username", rs.getString("username"),
                        "hue", rs.getInt("hue"),
                        // The client shows admins the admin view; admins cannot use player APIs.
                        "role", rs.getString("role")), UUID.fromString(jwt.getSubject()));
        if (users.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(users.getFirst());
    }

    private String sessionCookie(String value, Duration maxAge) {
        return cookies.session(value, maxAge);
    }
}
