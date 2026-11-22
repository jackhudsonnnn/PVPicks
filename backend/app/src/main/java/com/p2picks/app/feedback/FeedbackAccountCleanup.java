package com.p2picks.app.feedback;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Feedback can hold personal details, so a deleted account's feedback is removed, not just unlinked. */
@Component
public class FeedbackAccountCleanup {
    private final JdbcTemplate jdbc;

    FeedbackAccountCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(UUID userId) {
        jdbc.update("DELETE FROM feedback WHERE user_id = ?", userId);
    }
}
