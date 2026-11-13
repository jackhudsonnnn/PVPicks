package com.p2picks.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SettlementReviewService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, BAD_REQUEST }
    record Review(boolean authenticated, boolean player, boolean accessible, UUID pendingReviewRevision) {}
    record PendingReview(UUID otherUserId, UUID pendingReviewRevision) {}
    record PendingReviews(boolean authenticated, boolean player, List<PendingReview> reviews) {}
    static class StaleReview extends RuntimeException {}
    static class ReviewRequired extends RuntimeException {}

    private static final String OPERATION = "settlement.review.acknowledge.v1";
    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;

    SettlementReviewService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
    }

    PendingReviews listPending(Jwt jwt) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return jdbc.query("""
                SELECT viewer.role, review.other_user_id, review.revision
                FROM users viewer JOIN auth_sessions session ON session.user_id = viewer.id
                LEFT JOIN settlement_pair_reviews review ON review.user_id = viewer.id
                    AND review.reviewed_at IS NULL AND viewer.role = 'PLAYER'
                    AND EXISTS (
                        SELECT 1 FROM settlement_lines l
                        WHERE (l.payer_id = viewer.id AND l.payee_id = review.other_user_id)
                           OR (l.payee_id = viewer.id AND l.payer_id = review.other_user_id)
                    )
                WHERE viewer.id = ? AND session.id = ? AND viewer.status = 'ACTIVE'
                  AND session.revoked_at IS NULL AND session.expires_at > clock_timestamp()
                ORDER BY review.updated_at DESC, review.other_user_id
                """, rs -> {
                    if (!rs.next()) return new PendingReviews(false, false, List.of());
                    boolean player = "PLAYER".equals(rs.getString("role"));
                    List<PendingReview> pending = new ArrayList<>();
                    do {
                        UUID other = rs.getObject("other_user_id", UUID.class);
                        if (other != null) {
                            pending.add(new PendingReview(other, rs.getObject("revision", UUID.class)));
                        }
                    } while (rs.next());
                    return new PendingReviews(true, player, List.copyOf(pending));
                }, actor, UUID.fromString(jwt.getId()));
    }

    Review read(Jwt jwt, UUID other) {
        UUID actor = UUID.fromString(jwt.getSubject());
        List<Review> rows = jdbc.query("""
                SELECT viewer.role, EXISTS (
                    SELECT 1 FROM settlement_lines l
                    WHERE (l.payer_id = viewer.id AND l.payee_id = ?)
                       OR (l.payee_id = viewer.id AND l.payer_id = ?)
                ) AS accessible, review.revision
                FROM users viewer JOIN auth_sessions session ON session.user_id = viewer.id
                LEFT JOIN settlement_pair_reviews review
                  ON review.user_id = viewer.id AND review.other_user_id = ? AND review.reviewed_at IS NULL
                WHERE viewer.id = ? AND session.id = ? AND viewer.status = 'ACTIVE'
                  AND session.revoked_at IS NULL AND session.expires_at > clock_timestamp()
                """, (rs, row) -> new Review(true, "PLAYER".equals(rs.getString("role")),
                rs.getBoolean("accessible"), rs.getObject("revision", UUID.class)),
                other, other, other, actor, UUID.fromString(jwt.getId()));
        return rows.isEmpty() ? new Review(false, false, false, null) : rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireReviewed(UUID actor, UUID other) {
        Boolean pending = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_pair_reviews
                    WHERE user_id = ? AND other_user_id = ? AND reviewed_at IS NULL)
                """, Boolean.class, actor, other);
        if (Boolean.TRUE.equals(pending)) throw new ReviewRequired();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReset(UUID actor, UUID other) {
        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_pair_reviews (user_id, other_user_id, revision)
                VALUES (?, ?, ?), (?, ?, ?)
                ON CONFLICT (user_id, other_user_id) DO UPDATE
                SET revision = EXCLUDED.revision, reviewed_at = NULL, updated_at = clock_timestamp()
                """, actor, other, revision, other, actor, revision);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshPending(UUID actor, UUID other) {
        jdbc.update("""
                UPDATE settlement_pair_reviews SET revision = ?, updated_at = clock_timestamp()
                WHERE reviewed_at IS NULL
                  AND ((user_id = ? AND other_user_id = ?) OR (user_id = ? AND other_user_id = ?))
                """, UUID.randomUUID(), actor, other, other, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshPendingIfOpen(UUID actor, UUID other) {
        Boolean open = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_lines
                    WHERE ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                      AND status NOT IN ('paid', 'forgiven'))
                """, Boolean.class, actor, other, other, actor);
        if (Boolean.TRUE.equals(open)) refreshPending(actor, other);
    }

    @Transactional
    public Outcome acknowledge(Jwt jwt, UUID other, UUID revision, String key) {
        if (!IdempotencyGuard.validKey(key) || other == null || revision == null) {
            throw new IllegalArgumentException("Invalid settlement review");
        }
        UUID actor = UUID.fromString(jwt.getSubject());
        Map<UUID, String> users = authentication.lockActiveWithTargets(jwt, List.of(other));
        if (users == null) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return Outcome.FORBIDDEN;
        }
        if (actor.equals(other)) return Outcome.BAD_REQUEST;
        List<UUID> current = jdbc.query("""
                SELECT revision FROM settlement_pair_reviews
                WHERE user_id = ? AND other_user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject("revision", UUID.class), actor, other);
        if (current.isEmpty()) return Outcome.NOT_FOUND;
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, OPERATION, other + "|" + revision);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!current.getFirst().equals(revision)) throw new StaleReview();
        jdbc.update("""
                UPDATE settlement_pair_reviews SET reviewed_at = COALESCE(reviewed_at, clock_timestamp())
                WHERE user_id = ? AND other_user_id = ?
                """, actor, other);
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }
}
