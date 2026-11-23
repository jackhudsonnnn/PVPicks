package com.p2picks.app.chat;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a deleted account's personal chat data: the text, GIFs and polls it wrote (their reactions and votes go
 * with them, and reports keep their snapshot), its reactions, its poll votes and its read positions. Bet and settlement cards it appears on stay,
 * showing "Deleted user".
 */
@Component
public class ChatAccountCleanup {
    private final JdbcTemplate jdbc;

    ChatAccountCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        jdbc.update("DELETE FROM reactions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM poll_votes WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_messages WHERE author_id = ? AND kind IN ('text', 'gif', 'poll')", userId);
        jdbc.update("DELETE FROM chat_read_positions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM gif_favorites WHERE user_id = ?", userId);
    }
}
