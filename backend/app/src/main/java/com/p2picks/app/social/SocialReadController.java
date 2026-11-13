package com.p2picks.app.social;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
class SocialReadController {
    private final JdbcTemplate jdbc;

    SocialReadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/me/relationships")
    ResponseEntity<?> relationships(@AuthenticationPrincipal Jwt jwt) {
        List<Relationships> result = jdbc.query("""
                SELECT ARRAY(
                    SELECT f.followee_id::text FROM follows f
                    JOIN users other_user ON other_user.id = f.followee_id
                    WHERE f.follower_id = viewer.id AND other_user.status <> 'DELETED'
                      AND NOT EXISTS (
                          SELECT 1 FROM blocks b
                          WHERE (b.blocker_id = viewer.id AND b.blocked_id = other_user.id)
                             OR (b.blocker_id = other_user.id AND b.blocked_id = viewer.id))
                    ORDER BY f.followee_id
                ) AS following_ids,
                ARRAY(
                    SELECT f.follower_id::text FROM follows f
                    JOIN users other_user ON other_user.id = f.follower_id
                    WHERE f.followee_id = viewer.id AND other_user.status <> 'DELETED'
                      AND NOT EXISTS (
                          SELECT 1 FROM blocks b
                          WHERE (b.blocker_id = viewer.id AND b.blocked_id = other_user.id)
                             OR (b.blocker_id = other_user.id AND b.blocked_id = viewer.id))
                    ORDER BY f.follower_id
                ) AS follower_ids,
                ARRAY(
                    SELECT b.blocked_id::text FROM blocks b
                    JOIN users other_user ON other_user.id = b.blocked_id
                    WHERE b.blocker_id = viewer.id AND other_user.status <> 'DELETED'
                    ORDER BY b.blocked_id
                ) AS blocked_ids
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                """, (rs, row) -> new Relationships(
                        ids(rs.getArray("following_ids")), ids(rs.getArray("follower_ids")),
                        ids(rs.getArray("blocked_ids"))),
                UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (result.isEmpty()) {
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "unauthorized", "message", "Authentication required or credentials invalid."));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.getFirst());
    }

    @GetMapping("/me/invites")
    ResponseEntity<?> invitations(@AuthenticationPrincipal Jwt jwt) {
        List<InvitationRow> rows = jdbc.query("""
                SELECT invitation.id, invitation.table_id, invitation.table_name, invitation.table_emoji,
                       invitation.from_id, invitation.user_id, invitation.created_at
                FROM auth_sessions session
                JOIN users viewer ON viewer.id = session.user_id
                LEFT JOIN LATERAL (
                    SELECT i.id, i.table_id, t.name AS table_name, t.emoji AS table_emoji,
                           i.from_id, i.user_id, i.created_at
                    FROM table_invites i
                    JOIN tables t ON t.id = i.table_id AND t.archived_at IS NULL
                    JOIN users sender ON sender.id = i.from_id AND sender.status <> 'DELETED'
                    WHERE i.user_id = viewer.id AND i.status = 'PENDING'
                      AND EXISTS (SELECT 1 FROM table_members host
                          WHERE host.table_id = i.table_id AND host.user_id = i.from_id AND host.role = 'HOST')
                      AND EXISTS (SELECT 1 FROM follows f
                          WHERE f.follower_id = viewer.id AND f.followee_id = i.from_id)
                      AND (CASE WHEN EXISTS (SELECT 1 FROM follows f
                              WHERE f.follower_id = i.from_id AND f.followee_id = viewer.id)
                          THEN COALESCE((SELECT p.friends FROM invite_preferences p WHERE p.user_id = viewer.id), 'open')
                          ELSE COALESCE((SELECT p.following FROM invite_preferences p WHERE p.user_id = viewer.id), 'request')
                          END) <> 'closed'
                      AND NOT EXISTS (
                          SELECT 1 FROM blocks b
                          WHERE (b.blocker_id = viewer.id AND b.blocked_id = i.from_id)
                             OR (b.blocker_id = i.from_id AND b.blocked_id = viewer.id))
                      AND NOT EXISTS (
                          SELECT 1 FROM table_members m
                          WHERE m.table_id = i.table_id AND m.user_id = viewer.id)
                ) invitation ON true
                WHERE session.id = ? AND viewer.id = ? AND session.revoked_at IS NULL
                  AND session.expires_at > now() AND viewer.status = 'ACTIVE'
                ORDER BY invitation.created_at DESC NULLS LAST, invitation.id
                """, (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new InvitationRow(id == null ? null : new TableInvite(
                            id, rs.getObject("table_id", UUID.class), rs.getString("table_name"),
                            rs.getString("table_emoji"), rs.getObject("from_id", UUID.class),
                            rs.getObject("user_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class)));
                }, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
        if (rows.isEmpty()) {
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "unauthorized", "message", "Authentication required or credentials invalid."));
        }
        List<TableInvite> invitations = new ArrayList<>();
        for (InvitationRow row : rows) {
            if (row.invite() != null) {
                invitations.add(row.invite());
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invitations);
    }

    private static List<UUID> ids(Array values) throws SQLException {
        return Arrays.stream((String[]) values.getArray()).map(UUID::fromString).toList();
    }

    private record Relationships(List<UUID> followingIds, List<UUID> followerIds, List<UUID> blockedIds) {}

    private record InvitationRow(TableInvite invite) {}

    private record TableInvite(
            UUID id, UUID tableId, String tableName, String tableEmoji, UUID fromId, UUID userId,
            OffsetDateTime createdAt) {}
}
