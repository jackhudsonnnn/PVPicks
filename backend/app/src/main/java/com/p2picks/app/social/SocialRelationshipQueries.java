package com.p2picks.app.social;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class SocialRelationshipQueries {
    private final JdbcTemplate jdbc;

    SocialRelationshipQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean blocked(UUID first, UUID second) {
        Boolean blocked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM blocks WHERE
                    (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, first, second, second, first);
        return Objects.requireNonNull(blocked, "Block query returned null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelIneligibleForRecipient(UUID recipient) {
        jdbc.update("""
                UPDATE table_invites i SET status = 'CANCELLED'
                WHERE i.user_id = ? AND i.status = 'PENDING' AND (
                    NOT EXISTS (SELECT 1 FROM table_members m
                        WHERE m.table_id = i.table_id AND m.user_id = i.from_id AND m.role = 'HOST')
                    OR NOT EXISTS (SELECT 1 FROM follows f
                        WHERE f.follower_id = i.user_id AND f.followee_id = i.from_id)
                    OR (CASE WHEN EXISTS (SELECT 1 FROM follows f
                            WHERE f.follower_id = i.from_id AND f.followee_id = i.user_id)
                        THEN COALESCE((SELECT p.friends FROM invite_preferences p WHERE p.user_id = i.user_id), 'open')
                        ELSE COALESCE((SELECT p.following FROM invite_preferences p WHERE p.user_id = i.user_id), 'request')
                        END) = 'closed')
                """, recipient);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelIneligibleForPair(UUID first, UUID second) {
        jdbc.update("""
                UPDATE table_invites i SET status = 'CANCELLED'
                WHERE i.status = 'PENDING' AND
                    ((i.user_id = ? AND i.from_id = ?) OR (i.user_id = ? AND i.from_id = ?))
                    AND (NOT EXISTS (SELECT 1 FROM follows f
                            WHERE f.follower_id = i.user_id AND f.followee_id = i.from_id)
                        OR (CASE WHEN EXISTS (SELECT 1 FROM follows f
                                WHERE f.follower_id = i.from_id AND f.followee_id = i.user_id)
                            THEN COALESCE((SELECT p.friends FROM invite_preferences p WHERE p.user_id = i.user_id), 'open')
                            ELSE COALESCE((SELECT p.following FROM invite_preferences p WHERE p.user_id = i.user_id), 'request')
                            END) = 'closed')
                """, first, second, second, first);
    }
}
