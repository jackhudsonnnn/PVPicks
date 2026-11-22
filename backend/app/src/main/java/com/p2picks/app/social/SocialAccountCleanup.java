package com.p2picks.app.social;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes a deleted account's relationships and invite preferences, in both directions. */
@Component
public class SocialAccountCleanup {
    private final JdbcTemplate jdbc;

    SocialAccountCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        jdbc.update("DELETE FROM follows WHERE follower_id = ? OR followee_id = ?", userId, userId);
        jdbc.update("DELETE FROM blocks WHERE blocker_id = ? OR blocked_id = ?", userId, userId);
        jdbc.update("DELETE FROM invite_preferences WHERE user_id = ?", userId);
    }
}
