package com.p2picks.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SettingsReadController {
    private final JdbcTemplate jdbc;
    private final DebtQuery debtQuery;

    SettingsReadController(JdbcTemplate jdbc, DebtQuery debtQuery) {
        this.jdbc = jdbc;
        this.debtQuery = debtQuery;
    }

    @GetMapping("/me/debt")
    ResponseEntity<?> debt(@AuthenticationPrincipal Jwt jwt) {
        var amount = debtQuery.read(jwt);
        if (amount.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Debt(amount.orElseThrow().longValueExact()));
    }

    @GetMapping("/me/debt-cap")
    ResponseEntity<?> debtCap(@AuthenticationPrincipal Jwt jwt) {
        List<DebtCap> caps = jdbc.query("""
                SELECT cap.cap_cents
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN debt_caps cap ON cap.user_id = viewer.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new DebtCap(rs.getObject("cap_cents", Integer.class)),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (caps.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(caps.getFirst());
    }

    @GetMapping("/me/notification-preferences")
    ResponseEntity<?> notificationPreferences(@AuthenticationPrincipal Jwt jwt) {
        List<NotificationPreferences> preferences = jdbc.query("""
                SELECT COALESCE(pref.enabled, true) AS enabled,
                       COALESCE(pref.errors, true) AS errors,
                       COALESCE(pref.ticket_status, true) AS ticket_status,
                       COALESCE(pref.ticket_scope, 'mine') AS ticket_scope,
                       COALESCE(pref.chats, true) AS chats,
                       COALESCE(pref.invites, true) AS invites,
                       ARRAY(SELECT muted.table_id::text FROM notification_muted_tables muted
                             WHERE muted.user_id = viewer.id ORDER BY muted.table_id) AS muted_tables
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN notification_preferences pref ON pref.user_id = viewer.id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new NotificationPreferences(
                        rs.getBoolean("enabled"), rs.getBoolean("errors"), rs.getBoolean("ticket_status"),
                        rs.getString("ticket_scope"), rs.getBoolean("chats"), rs.getBoolean("invites"),
                        Arrays.stream((String[]) rs.getArray("muted_tables").getArray()).map(UUID::fromString).toList()),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (preferences.isEmpty()) {
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.getFirst());
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
            return unauthorized();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.getFirst());
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "unauthorized", "message", "Authentication required or credentials invalid."));
    }

    private record DebtCap(@JsonInclude(JsonInclude.Include.ALWAYS) Integer capCents) {}

    private record Debt(long debtCents) {}

    private record NotificationPreferences(
            boolean enabled, boolean errors, boolean ticketStatus, String ticketScope,
            boolean chats, boolean invites, List<UUID> mutedTableIds) {}

    private record InvitePreferences(String friends, String following) {}
}
