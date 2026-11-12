package com.p2picks.app;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvitePreferencesService {
    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;
    private final SocialRelationshipQueries relationships;

    InvitePreferencesService(
            JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication,
            SocialRelationshipQueries relationships) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
        this.relationships = relationships;
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED }

    record Preferences(String friends, String following) {}

    @Transactional
    public Outcome replace(Jwt jwt, String key, Preferences preferences) {
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) {
            return Outcome.UNAUTHORIZED;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "invite-preferences.replace.v1",
                preferences.friends() + "|" + preferences.following());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return Outcome.REPLAYED;
        }
        jdbc.update("""
                INSERT INTO invite_preferences (user_id, friends, following)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET friends = EXCLUDED.friends,
                    following = EXCLUDED.following, updated_at = clock_timestamp()
                """, userId, preferences.friends(), preferences.following());
        relationships.cancelIneligibleForRecipient(userId);
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}
