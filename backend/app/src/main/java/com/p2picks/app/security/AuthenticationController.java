package com.p2picks.app.security;

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
    private final DevelopmentSessions sessions;
    private final CsrfTokenRepository csrfTokens;
    private final JdbcTemplate jdbc;

    AuthenticationController(DevelopmentSessions sessions, CsrfTokenRepository csrfTokens, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.csrfTokens = csrfTokens;
        this.jdbc = jdbc;
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
        String token = sessions.login(input.path("username").textValue(), input.path("password").textValue(), previousToken);
        if (token == null) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, Duration.ofHours(8)));
        csrfTokens.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
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
                SELECT id, username::text, hue FROM users WHERE id = ? AND status = 'ACTIVE'
                """, (rs, row) -> Map.<String, Object>of(
                        "id", rs.getObject("id", UUID.class),
                        "username", rs.getString("username"),
                        "hue", rs.getInt("hue")), UUID.fromString(jwt.getSubject()));
        if (users.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(users.getFirst());
    }

    private static String sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from("session", value).httpOnly(true).secure(false)
                .sameSite("Strict").path("/").maxAge(maxAge).build().toString();
    }

    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
}
