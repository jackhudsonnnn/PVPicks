package com.p2picks.app;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationInboxService {
    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;

    NotificationInboxService(
            JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED }

    @Transactional
    public Outcome markRead(Jwt jwt, String key) {
        return mutate(jwt, key, false);
    }

    @Transactional
    public Outcome clear(Jwt jwt, String key) {
        return mutate(jwt, key, true);
    }

    private Outcome mutate(Jwt jwt, String key, boolean clear) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) {
            return Outcome.UNAUTHORIZED;
        }
        String operation = clear ? "notifications.clear.v1" : "notifications.read-all.v1";
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation, "");
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return Outcome.REPLAYED;
        }
        if (clear) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", userId);
        } else {
            jdbc.update("""
                    UPDATE notifications SET read_at = clock_timestamp()
                    WHERE recipient_id = ? AND read_at IS NULL
                    """, userId);
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}
