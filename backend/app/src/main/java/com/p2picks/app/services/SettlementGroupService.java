package com.p2picks.app;

import com.p2picks.app.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SettlementGroupService {
    enum Action { SENT, PAID, UNPAID, FORGIVEN, ASK, DECLINE }
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, BAD_REQUEST }
    record Group(UUID otherUserId, BigInteger netCents, String revision, List<Line> lines, UUID pendingReviewRevision) {}
    record Groups(boolean authenticated, boolean player, List<Group> groups) {}
    static class StaleRevision extends RuntimeException {}
    static class InvalidTransition extends RuntimeException {}
    static class NoOpenReceipts extends RuntimeException {}

    private static final String OPERATION = "settlement.group.transition.v1";
    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SettlementReviewService reviews;

    SettlementGroupService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            SettlementReviewService reviews) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.reviews = reviews;
    }

    Groups read(Jwt jwt, boolean includeHistory) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return jdbc.query("""
                WITH viewer AS (
                    SELECT u.id, u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                    WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                      AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                )
                SELECT viewer.role AS viewer_role, review.revision AS review_revision, l.* FROM viewer LEFT JOIN settlement_lines l
                    ON (l.payer_id = viewer.id OR l.payee_id = viewer.id)
                    AND (? OR l.status NOT IN ('paid', 'forgiven')) AND viewer.role = 'PLAYER'
                LEFT JOIN settlement_pair_reviews review ON review.user_id = viewer.id
                    AND review.other_user_id = CASE WHEN l.payer_id = viewer.id THEN l.payee_id ELSE l.payer_id END
                    AND review.reviewed_at IS NULL
                ORDER BY l.created_at DESC, l.id DESC
                """, rs -> {
                    if (!rs.next()) return new Groups(false, false, List.of());
                    boolean player = "PLAYER".equals(rs.getString("viewer_role"));
                    Map<UUID, List<Line>> byPerson = new LinkedHashMap<>();
                    Map<UUID, UUID> pendingReviews = new LinkedHashMap<>();
                    do {
                        if (rs.getObject("id", UUID.class) == null) continue;
                        Line line = SettlementReceiptService.line(rs);
                        UUID other = line.payerId().equals(actor) ? line.payeeId() : line.payerId();
                        byPerson.computeIfAbsent(other, ignored -> new ArrayList<>()).add(line);
                        UUID review = rs.getObject("review_revision", UUID.class);
                        if (review != null) pendingReviews.put(other, review);
                    } while (rs.next());
                    List<Group> groups = new ArrayList<>();
                    byPerson.forEach((other, lines) -> groups.add(group(actor, other, lines, pendingReviews.get(other))));
                    return new Groups(true, player, List.copyOf(groups));
                }, actor, UUID.fromString(jwt.getId()), includeHistory);
    }

    @Transactional
    public Outcome transition(Jwt jwt, UUID other, Action action, String expectedRevision, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (other == null || action == null || expectedRevision == null || expectedRevision.isEmpty()) {
            throw new IllegalArgumentException("Invalid group action");
        }
        UUID actor = UUID.fromString(jwt.getSubject());
        Map<UUID, String> lockedUsers = authentication.lockActiveWithTargets(jwt, List.of(other));
        if (lockedUsers == null) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return Outcome.FORBIDDEN;
        }
        if (actor.equals(other)) return Outcome.BAD_REQUEST;
        if (!lockedUsers.containsKey(other)) return Outcome.NOT_FOUND;
        Boolean hasHistory = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_lines
                    WHERE (payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                """, Boolean.class, actor, other, other, actor);
        if (!Boolean.TRUE.equals(hasHistory)) return Outcome.NOT_FOUND;
        List<Line> lines = jdbc.query("""
                SELECT * FROM settlement_lines
                WHERE ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                  AND status NOT IN ('paid', 'forgiven')
                ORDER BY id FOR UPDATE
                """, (rs, row) -> SettlementReceiptService.line(rs), actor, other, other, actor);
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, OPERATION,
                other + "|" + action.name() + "|" + expectedRevision);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (lines.isEmpty()) throw new NoOpenReceipts();
        Group group = group(actor, other, lines, null);
        if (!group.revision().equals(expectedRevision)) throw new StaleRevision();
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null) throw new IllegalStateException("Database clock is unavailable");

        if (group.netCents().signum() == 0) {
            if (action != Action.PAID) throw new InvalidTransition();
        } else {
            UUID payer = group.netCents().signum() < 0 ? actor : other;
            UUID payee = payer.equals(actor) ? other : actor;
            boolean payerAction = action == Action.SENT || action == Action.ASK;
            if (!(payerAction ? payer : payee).equals(actor)) {
                idempotency.discardNewClaim(actor, key);
                return Outcome.FORBIDDEN;
            }
            if (payerAction) reviews.requireReviewed(actor, other);
            List<Line> directional = lines.stream().filter(line -> line.payerId().equals(payer)).toList();
            boolean sent = directional.stream().anyMatch(line -> line.status().equals("sent"));
            boolean asked = directional.stream().anyMatch(Line::hasOpenRequest);
            OffsetDateTime declined = directional.stream().map(Line::forgiveDeclinedAt)
                    .filter(value -> value != null).max(Comparator.naturalOrder()).orElse(null);
            switch (action) {
                case SENT, FORGIVEN -> {
                    if (sent) throw new InvalidTransition();
                }
                case UNPAID -> {
                    if (!sent) throw new InvalidTransition();
                }
                case ASK -> {
                    if (sent || asked || (declined != null && now.isBefore(declined.plusHours(24)))) {
                        throw new InvalidTransition();
                    }
                }
                case DECLINE -> {
                    if (sent || !asked) throw new InvalidTransition();
                }
                case PAID -> { }
            }
        }
        for (Line line : lines) {
            String nextStatus = switch (action) {
                case SENT -> "sent";
                case PAID -> "paid";
                case UNPAID -> "unpaid";
                case FORGIVEN -> "forgiven";
                case ASK -> line.status().equals("sent") ? "sent" : "forgive_asked";
                case DECLINE -> line.status().equals("sent") ? "sent" : "unpaid";
            };
            OffsetDateTime requested = action == Action.ASK ? now : null;
            OffsetDateTime declined = action == Action.DECLINE ? now : null;
            int updated = jdbc.update("""
                    UPDATE settlement_lines SET status = ?, forgive_requested_at = ?, forgive_declined_at = ?,
                        revision = revision + 1, updated_at = ? WHERE id = ?
                    """, nextStatus, requested, declined, now, line.id());
            if (updated != 1) throw new IllegalStateException("Locked receipt disappeared");
        }
        jdbc.update("""
                INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'settlement_pair', ?, 'settlement.group.changed.v1',
                        jsonb_build_object('userId', ?::text, 'otherUserId', ?::text))
                """, UUID.randomUUID(), actor + ":" + other, actor.toString(), other.toString());
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private static Group group(UUID actor, UUID other, List<Line> lines, UUID pendingReviewRevision) {
        List<Line> open = lines.stream().filter(line -> !line.status().equals("paid")
                && !line.status().equals("forgiven")).toList();
        BigInteger net = BigInteger.ZERO;
        for (Line line : open) {
            BigInteger amount = BigInteger.valueOf(line.amountCents());
            net = net.add(line.payerId().equals(actor) ? amount.negate() : amount);
        }
        String identity = open.stream().sorted(Comparator.comparing(line -> line.id().toString()))
                .map(line -> line.id() + ":" + line.revision() + ";").collect(Collectors.joining());
        String revision;
        try {
            revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        return new Group(other, net, revision, List.copyOf(lines), pendingReviewRevision);
    }
}
