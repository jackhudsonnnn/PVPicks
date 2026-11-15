package com.p2picks.app.settlement;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementReceiptService {
    public enum Action { SENT, CONFIRM, DISPUTE, FORGIVE, ASK_FORGIVE, DECLINE_FORGIVE }
    public enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    record Line(UUID id, UUID settlementId, UUID tableId, UUID payerId, UUID payeeId, long amountCents,
                String status, int revision, OffsetDateTime forgiveRequestedAt, OffsetDateTime forgiveDeclinedAt,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        boolean hasOpenRequest() {
            return forgiveRequestedAt != null || status.equals("forgive_asked");
        }

        boolean unpaid() {
            return status.equals("unpaid") || status.equals("disputed") || status.equals("forgive_asked");
        }
    }
    /** A receipt as the API returns it: the line plus what the viewer may do with it. */
    public record LineView(UUID id, UUID settlementId, UUID tableId, UUID payerId, UUID payeeId, long amountCents,
                    String status, int revision, OffsetDateTime forgiveRequestedAt, OffsetDateTime forgiveDeclinedAt,
                    OffsetDateTime createdAt, OffsetDateTime updatedAt, List<ReceiptActions.Available> actions) {
        static LineView of(Line line, List<ReceiptActions.Available> actions) {
            return new LineView(line.id(), line.settlementId(), line.tableId(), line.payerId(), line.payeeId(),
                    line.amountCents(), line.status(), line.revision(), line.forgiveRequestedAt(),
                    line.forgiveDeclinedAt(), line.createdAt(), line.updatedAt(), actions);
        }
    }
    public record Result(Outcome outcome, LineView line) {}
    record History(boolean authenticated, boolean player, List<LineView> lines) {}

    static class StaleRevision extends RuntimeException {}
    static class InvalidTransition extends RuntimeException {}
    static class AffectedUsersChanged extends RuntimeException {
        AffectedUsersChanged() {
            super("Affected users changed while acquiring locks; retry the transaction");
        }
    }

    private static final String OPERATION = "settlement.line.transition.v1";
    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SettlementReviewService reviews;
    private final Outbox outbox;

    SettlementReceiptService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            SettlementReviewService reviews, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.reviews = reviews;
        this.outbox = outbox;
    }

    History history(Jwt jwt, UUID otherId, UUID tableId) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return jdbc.query("""
                WITH viewer AS (
                    SELECT u.id, u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                    WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                      AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                )
                SELECT viewer.role AS viewer_role, clock_timestamp() AS read_at,
                    EXISTS (SELECT 1 FROM settlement_pair_reviews r WHERE r.user_id = viewer.id
                            AND r.other_user_id = ? AND r.reviewed_at IS NULL) AS review_pending, l.*
                FROM viewer LEFT JOIN settlement_lines l ON
                    ((l.payer_id = viewer.id AND l.payee_id = ?)
                      OR (l.payee_id = viewer.id AND l.payer_id = ?))
                    AND (?::uuid IS NULL OR l.table_id = ?)
                    AND viewer.role = 'PLAYER'
                ORDER BY CASE WHEN l.status IN ('paid', 'forgiven') THEN 1 ELSE 0 END,
                         l.created_at DESC, l.id DESC
                """, rs -> {
                    if (!rs.next()) return new History(false, false, List.of());
                    boolean player = "PLAYER".equals(rs.getString("viewer_role"));
                    OffsetDateTime now = rs.getObject("read_at", OffsetDateTime.class);
                    boolean reviewPending = rs.getBoolean("review_pending");
                    List<LineView> lines = new ArrayList<>();
                    do {
                        if (rs.getObject("id", UUID.class) == null) continue;
                        Line line = line(rs);
                        lines.add(LineView.of(line, ReceiptActions.forLine(line, actor, now, reviewPending)));
                    } while (rs.next());
                    return new History(true, player, List.copyOf(lines));
                }, actor, UUID.fromString(jwt.getId()), otherId, otherId, otherId, tableId, tableId);
    }

    @Transactional
    public Result transition(Jwt jwt, UUID lineId, Action action, int expectedRevision, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (expectedRevision < 0 || action == null) throw new IllegalArgumentException("Invalid receipt action");
        UUID actor = UUID.fromString(jwt.getSubject());
        List<UUID> hints = jdbc.query("""
                SELECT CASE WHEN payer_id = ? THEN payee_id ELSE payer_id END AS other_id
                FROM settlement_lines WHERE id = ? AND (payer_id = ? OR payee_id = ?)
                """, (rs, row) -> rs.getObject("other_id", UUID.class), actor, lineId, actor, actor);
        Map<UUID, String> lockedUsers = authentication.lockActiveWithTargets(jwt, hints);
        if (lockedUsers == null) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return new Result(Outcome.FORBIDDEN, null);
        }
        List<Line> rows = jdbc.query("""
                SELECT * FROM settlement_lines WHERE id = ? AND (payer_id = ? OR payee_id = ?) FOR UPDATE
                """, (rs, row) -> line(rs), lineId, actor, actor);
        authentication.requireUnexpired(jwt);
        if (rows.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
        Line current = rows.getFirst();
        if (!lockedUsers.containsKey(current.payerId()) || !lockedUsers.containsKey(current.payeeId())) {
            throw new AffectedUsersChanged();
        }
        boolean payerAction = ReceiptActions.payerAction(action);
        if (!(payerAction ? current.payerId() : current.payeeId()).equals(actor)) {
            return new Result(Outcome.FORBIDDEN, null);
        }
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, OPERATION,
                lineId + "|" + action.name() + "|" + expectedRevision, IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        if (claim == IdempotencyGuard.Claim.REPLAY) return new Result(Outcome.REPLAYED, view(current, actor));
        if (current.revision() != expectedRevision) throw new StaleRevision();
        if (payerAction) reviews.requireReviewed(actor, current.payeeId());

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null) throw new IllegalStateException("Database clock is unavailable");
        if (!ReceiptActions.lineAllowed(current, action, actor, now)) throw new InvalidTransition();
        String status = current.status();
        OffsetDateTime requested = current.forgiveRequestedAt();
        OffsetDateTime declined = current.forgiveDeclinedAt();
        switch (action) {
            case SENT -> status = "sent";
            case CONFIRM -> status = "paid";
            case DISPUTE -> status = requested == null ? "unpaid" : "forgive_asked";
            case FORGIVE -> status = "forgiven";
            case ASK_FORGIVE -> {
                status = "forgive_asked";
                requested = now;
            }
            case DECLINE_FORGIVE -> {
                status = "unpaid";
                requested = null;
                declined = now;
            }
        }
        int updated = jdbc.update("""
                UPDATE settlement_lines SET status = ?, forgive_requested_at = ?, forgive_declined_at = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ?
                """, status, requested, declined, now, lineId);
        if (updated != 1) throw new IllegalStateException("Locked receipt disappeared");
        if (action == Action.CONFIRM || action == Action.FORGIVE) {
            UUID other = current.payerId().equals(actor) ? current.payeeId() : current.payerId();
            reviews.refreshPendingIfOpen(actor, other);
        }
        outbox.append(OutboxEvent.SETTLEMENT_LINE_CHANGED, lineId,
                Map.of("lineId", lineId, "payerId", current.payerId(), "payeeId", current.payeeId()));
        Line result = jdbc.queryForObject("SELECT * FROM settlement_lines WHERE id = ?",
                (rs, row) -> line(rs), lineId);
        if (result == null) throw new IllegalStateException("Updated receipt disappeared");
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.UPDATED, view(result, actor));
    }

    /** A receipt with the actor's current actions, for transition responses. */
    private LineView view(Line line, UUID actor) {
        UUID other = line.payerId().equals(actor) ? line.payeeId() : line.payerId();
        Boolean reviewPending = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_pair_reviews
                    WHERE user_id = ? AND other_user_id = ? AND reviewed_at IS NULL)
                """, Boolean.class, actor, other);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        return LineView.of(line, ReceiptActions.forLine(line, actor, now, Boolean.TRUE.equals(reviewPending)));
    }

    static Line line(ResultSet rs) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class),
                rs.getObject("table_id", UUID.class), rs.getObject("payer_id", UUID.class),
                rs.getObject("payee_id", UUID.class), rs.getLong("amount_cents"), rs.getString("status"),
                rs.getInt("revision"), rs.getObject("forgive_requested_at", OffsetDateTime.class),
                rs.getObject("forgive_declined_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
