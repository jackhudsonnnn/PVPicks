package com.p2picks.app.social;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follows the server sets up itself (the LAN party makes everyone friends): no session or retry key. Runs inside
 * the caller's transaction.
 */
@Service
public class Friendships {
    private final JdbcTemplate jdbc;

    Friendships(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes two people follow each other, unless either has blocked the other. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void befriend(UUID a, UUID b) {
        if (a.equals(b)) return;
        Boolean blocked = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM blocks
                    WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?))
                """, Boolean.class, a, b, b, a);
        if (Boolean.TRUE.equals(blocked)) return;
        jdbc.update("""
                INSERT INTO follows (follower_id, followee_id) VALUES (?, ?), (?, ?)
                ON CONFLICT (follower_id, followee_id) DO NOTHING
                """, a, b, b, a);
    }
}
