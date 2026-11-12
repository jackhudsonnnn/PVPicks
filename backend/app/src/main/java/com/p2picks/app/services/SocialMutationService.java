package com.p2picks.app;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SocialMutationService {
    enum Operation {
        FOLLOW("social.follow.v1"),
        UNFOLLOW("social.unfollow.v1"),
        BLOCK("social.block.v1"),
        UNBLOCK("social.unblock.v1");

        private final String fingerprintName;

        Operation(String fingerprintName) {
            this.fingerprintName = fingerprintName;
        }
    }

    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, NOT_FOUND, BAD_REQUEST }

    private final JdbcTemplate jdbc;
    private final IdempotencyGuard idempotency;
    private final MutationAuthentication authentication;
    private final SocialRelationshipQueries relationships;

    SocialMutationService(JdbcTemplate jdbc, IdempotencyGuard idempotency, MutationAuthentication authentication,
            SocialRelationshipQueries relationships) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.authentication = authentication;
        this.relationships = relationships;
    }

    @Transactional
    public Outcome mutate(Jwt jwt, UUID targetId, String key, Operation operation) {
        UUID userId = UUID.fromString(jwt.getSubject());
        MutationAuthentication.PairStatus pair = authentication.lockActivePair(jwt, targetId);
        if (pair == MutationAuthentication.PairStatus.UNAUTHORIZED) {
            return Outcome.UNAUTHORIZED;
        }
        authentication.requireUnexpired(jwt);
        if (userId.equals(targetId)) {
            return Outcome.BAD_REQUEST;
        }
        if (pair == MutationAuthentication.PairStatus.TARGET_UNAVAILABLE) {
            return Outcome.NOT_FOUND;
        }
        if (operation == Operation.FOLLOW && relationships.blocked(userId, targetId)) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation.fingerprintName, targetId.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) {
            return Outcome.CONFLICT;
        }
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return Outcome.REPLAYED;
        }
        switch (operation) {
            case FOLLOW -> jdbc.update("""
                    INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)
                    ON CONFLICT (follower_id, followee_id) DO NOTHING
                    """, userId, targetId);
            case UNFOLLOW -> jdbc.update(
                    "DELETE FROM follows WHERE follower_id = ? AND followee_id = ?", userId, targetId);
            case BLOCK -> {
                jdbc.update("""
                        INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)
                        ON CONFLICT (blocker_id, blocked_id) DO NOTHING
                        """, userId, targetId);
                jdbc.update("""
                        DELETE FROM follows WHERE (follower_id = ? AND followee_id = ?)
                            OR (follower_id = ? AND followee_id = ?)
                        """, userId, targetId, targetId, userId);
                jdbc.update("""
                        UPDATE table_invites SET status = 'CANCELLED'
                        WHERE status = 'PENDING' AND ((from_id = ? AND user_id = ?)
                            OR (from_id = ? AND user_id = ?))
                        """, userId, targetId, targetId, userId);
            }
            case UNBLOCK -> jdbc.update(
                    "DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", userId, targetId);
        }
        if (operation == Operation.FOLLOW || operation == Operation.UNFOLLOW) {
            relationships.cancelIneligibleForPair(userId, targetId);
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

}
