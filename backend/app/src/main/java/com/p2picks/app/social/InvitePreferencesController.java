package com.p2picks.app.social;

import static com.p2picks.app.common.ApiErrors.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InvitePreferencesController {
    private static final Set<String> MODES = Set.of("open", "request", "closed");
    private final InvitePreferencesService settings;
    private final JdbcTemplate jdbc;

    InvitePreferencesController(InvitePreferencesService settings, JdbcTemplate jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @GetMapping("/me/invite-preferences")
    ResponseEntity<?> invitePreferences(@AuthenticationPrincipal Jwt jwt) {
        List<InvitePreferences> preferences = jdbc.query("""
                SELECT COALESCE(pref.friends, 'open') AS friends,
                       COALESCE(pref.following, 'request') AS following
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN invite_preferences pref ON pref.user_id = viewer.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new InvitePreferences(rs.getString("friends"), rs.getString("following")),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (preferences.isEmpty()) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.getFirst());
    }

    @PutMapping("/me/invite-preferences")
    ResponseEntity<?> replace(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode input) {
        if (!IdempotencyGuard.validKey(key)) {
            return error(400, "bad_request", "A valid Idempotency-Key is required.");
        }
        if (!input.isObject() || input.size() != 2 || !input.path("friends").isTextual()
                || !input.path("following").isTextual()
                || !MODES.contains(input.path("friends").textValue())
                || !MODES.contains(input.path("following").textValue())) {
            return error(400, "bad_request", "Invalid invitation preferences.");
        }
        var preferences = new InvitePreferencesService.Preferences(
                input.path("friends").textValue(), input.path("following").textValue());
        try {
            return switch (settings.replace(jwt, key, preferences)) {
                case UPDATED, REPLAYED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
                case CONFLICT -> error(409, "conflict", "Idempotency key was used for a different request.");
                case UNAUTHORIZED -> error(401, "unauthorized", "Authentication required or credentials invalid.");
            };
        } catch (MutationAuthentication.ExpiredSession exception) {
            return error(401, "unauthorized", "Authentication required or credentials invalid.");
        }
    }


    private record InvitePreferences(String friends, String following) {}
}
