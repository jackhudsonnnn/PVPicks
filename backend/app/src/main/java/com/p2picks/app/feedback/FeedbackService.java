package com.p2picks.app.feedback;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.security.MutationAuthentication;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FeedbackService {
    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;

    FeedbackService(JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
    }

    enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED }

    record Submission(String kind, String text, String link) {
        String canonical() {
            return kind + "|" + text.length() + ":" + text + "|"
                    + (link == null ? "-1:" : link.length() + ":" + link);
        }
    }

    @Transactional
    public Outcome submit(Jwt jwt, String key, Submission submission) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) {
            return Outcome.UNAUTHORIZED;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "feedback.submit.v1",
                submission.canonical(), IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return Outcome.REPLAYED;
        }
        jdbc.update("INSERT INTO feedback (id, user_id, kind, text, link) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, submission.kind(), submission.text(), submission.link());
        authentication.requireUnexpired(jwt);
        return Outcome.CREATED;
    }
}
