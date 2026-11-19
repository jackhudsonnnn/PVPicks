package com.p2picks.app.settlement;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles (domain rules, "Settle-ups"): closing a chosen set of one pair's open receipts at once, rejecting a sent
 * settle within its window, and the pair's settle history. Rules are in {@link SettleRules}.
 */
@Service
public class SettleService {
    public enum Outcome { CREATED, REJECTED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, BAD_REQUEST }

    /** One receipt a settle closed, as History shows it. */
    public record SettleLine(UUID id, UUID tableId, UUID payerId, UUID payeeId, long amountCents,
            OffsetDateTime createdAt) {}

    /** A settle as the API returns it. {@code canReject} is for the viewer. */
    public record SettleView(UUID id, String kind, UUID senderId, UUID otherId, UUID payerId, UUID payeeId,
            long amountCents, OffsetDateTime createdAt, OffsetDateTime rejectBy, OffsetDateTime rejectedAt,
            boolean canReject, List<SettleLine> lines) {}

    public record Result(Outcome outcome, SettleView settle) {}
    record Page(boolean authenticated, boolean player, boolean accessible, List<SettleView> items,
            String nextCursor) {}

    /** The selection isn't all open receipts between the two of you any more; refresh and choose again. */
    static class StaleSelection extends RuntimeException {}
    /** The selection can't be settled this way (see {@link SettleRules#problem}). */
    static class InvalidSettle extends RuntimeException {
        InvalidSettle(String message) {
            super(message);
        }
    }
    /** Three sends between the pair in the last 24 hours. */
    static class SendLimit extends RuntimeException {}
    /** Not a sent settle to you that's still inside its window. */
    static class NotRejectable extends RuntimeException {}

    static final int PAGE = 20;
    private static final String CREATE = "settlement.settle.v1";
    private static final String REJECT = "settlement.settle.reject.v1";

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SettlementReviewService reviews;
    private final Outbox outbox;

    SettleService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            SettlementReviewService reviews, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.reviews = reviews;
        this.outbox = outbox;
    }

    @Transactional
    public Result create(Jwt jwt, UUID other, SettleRules.Kind kind, List<UUID> lineIds, String key) {
        if (!IdempotencyGuard.validKey(key) || other == null || kind == null || lineIds == null
                || lineIds.isEmpty() || lineIds.size() > SettleRules.MAX_LINES
                || lineIds.stream().distinct().count() != lineIds.size()) {
            throw new IllegalArgumentException("Invalid settle");
        }
        UUID actor = UUID.fromString(jwt.getSubject());
        Map<UUID, String> users = authentication.lockActiveWithTargets(jwt, List.of(other));
        if (users == null) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return new Result(Outcome.FORBIDDEN, null);
        }
        if (actor.equals(other)) return new Result(Outcome.BAD_REQUEST, null);
        if (!users.containsKey(other) || !hasReceipts(actor, other)) return new Result(Outcome.NOT_FOUND, null);
        List<String> sorted = lineIds.stream().map(UUID::toString).sorted().toList();
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, CREATE,
                other + "|" + kind.value() + "|" + String.join(",", sorted), IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            UUID settle = idempotency.responseReference(actor, key, CREATE);
            return new Result(Outcome.REPLAYED, views(List.of(settle), actor, now()).getFirst());
        }

        List<Line> selection = jdbc.query("""
                SELECT * FROM settlement_lines
                WHERE id = ANY (?::uuid[]) AND status = 'unpaid'
                  AND ((payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                ORDER BY id FOR UPDATE
                """, (rs, row) -> SettlementReceiptService.line(rs), "{" + String.join(",", sorted) + "}",
                actor, other, other, actor);
        if (selection.size() != lineIds.size()) throw new StaleSelection();
        reviews.requireReviewed(actor, other);
        String problem = SettleRules.problem(kind, selection, actor);
        if (problem != null) throw new InvalidSettle(problem);
        OffsetDateTime now = now();
        if (kind == SettleRules.Kind.SENT) {
            Integer sends = jdbc.queryForObject("""
                    SELECT count(*) FROM settles
                    WHERE least(sender_id, other_id) = least(?::uuid, ?::uuid)
                      AND greatest(sender_id, other_id) = greatest(?::uuid, ?::uuid)
                      AND kind = 'sent' AND created_at > ?::timestamptz - interval '24 hours'
                    """, Integer.class, actor, other, actor, other, now);
            if (sends != null && sends >= SettleRules.SENDS_PER_DAY) throw new SendLimit();
        }

        BigInteger net = SettleRules.net(selection, actor);
        UUID payer = net.signum() == 0 ? null : net.signum() < 0 ? actor : other;
        UUID payee = net.signum() == 0 ? null : payer.equals(actor) ? other : actor;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settles (id, sender_id, other_id, kind, payer_id, payee_id, amount_cents, created_at,
                    reject_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor, other, kind.value(), payer, payee, net.abs().longValueExact(), now,
                kind == SettleRules.Kind.SENT ? now.plus(SettleRules.REJECT_WINDOW) : null);
        jdbc.batchUpdate("INSERT INTO settle_lines (settle_id, line_id) VALUES (?, ?)",
                selection.stream().map(line -> new Object[] {id, line.id()}).toList());
        int closed = jdbc.update("""
                UPDATE settlement_lines SET status = 'paid', settle_id = ?, revision = revision + 1, updated_at = ?
                WHERE id = ANY (?::uuid[]) AND status = 'unpaid'
                """, id, now,
                "{" + String.join(",", sorted) + "}");
        if (closed != selection.size()) throw new IllegalStateException("Locked receipts changed");
        idempotency.saveResponseReference(actor, key, CREATE, id);
        changed(actor, other);
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.CREATED, views(List.of(id), actor, now).getFirst());
    }

    @Transactional
    public Result reject(Jwt jwt, UUID settleId, String key) {
        if (!IdempotencyGuard.validKey(key) || settleId == null) throw new IllegalArgumentException("Invalid reject");
        UUID actor = UUID.fromString(jwt.getSubject());
        List<UUID> senders = jdbc.queryForList("SELECT sender_id FROM settles WHERE id = ? AND other_id = ?",
                UUID.class, settleId, actor);
        Map<UUID, String> users = authentication.lockActiveWithTargets(jwt, senders);
        if (users == null) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return new Result(Outcome.FORBIDDEN, null);
        }
        if (senders.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
        UUID sender = senders.getFirst();
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, REJECT, settleId.toString(),
                IdempotencyGuard.SuccessStatus.OK);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return new Result(Outcome.REPLAYED, views(List.of(settleId), actor, now()).getFirst());
        }
        record Locked(String kind, OffsetDateTime rejectBy, OffsetDateTime rejectedAt) {}
        Locked settle = jdbc.queryForObject("SELECT kind, reject_by, rejected_at FROM settles WHERE id = ? FOR UPDATE",
                (rs, row) -> new Locked(rs.getString("kind"), rs.getObject("reject_by", OffsetDateTime.class),
                        rs.getObject("rejected_at", OffsetDateTime.class)), settleId);
        OffsetDateTime now = now();
        if (settle == null || !SettleRules.rejectable(settle.kind(), settle.rejectBy(), settle.rejectedAt(), now)) {
            throw new NotRejectable();
        }
        jdbc.update("UPDATE settles SET rejected_at = ? WHERE id = ?", now, settleId);
        jdbc.update("""
                UPDATE settlement_lines SET status = 'unpaid', settle_id = NULL, revision = revision + 1,
                    updated_at = ?
                WHERE settle_id = ?
                """, now, settleId);
        changed(actor, sender);
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.REJECTED, views(List.of(settleId), actor, now).getFirst());
    }

    /** A pair's settles, newest first, {@link #PAGE} at a time. */
    Page history(Jwt jwt, UUID other, String cursor) {
        UUID actor = UUID.fromString(jwt.getSubject());
        List<String> roles = jdbc.queryForList("""
                SELECT u.role FROM users u JOIN auth_sessions s ON s.user_id = u.id
                WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE'
                  AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                """, String.class, actor, UUID.fromString(jwt.getId()));
        if (roles.isEmpty()) return new Page(false, false, false, List.of(), null);
        if (!roles.getFirst().equals("PLAYER")) return new Page(true, false, false, List.of(), null);
        if (actor.equals(other) || !hasReceipts(actor, other)) return new Page(true, true, false, List.of(), null);
        OffsetDateTime before = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 2) throw new IllegalArgumentException("Invalid cursor");
            before = OffsetDateTime.parse(parts[0]);
            beforeId = UUID.fromString(parts[1]);
        }
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM settles
                WHERE least(sender_id, other_id) = least(?::uuid, ?::uuid)
                  AND greatest(sender_id, other_id) = greatest(?::uuid, ?::uuid)
                  AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, UUID.class, actor, other, actor, other, before, before, beforeId, PAGE + 1);
        boolean more = ids.size() > PAGE;
        List<SettleView> items = views(more ? ids.subList(0, PAGE) : ids, actor, now());
        String next = null;
        if (more) {
            SettleView last = items.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new Page(true, true, true, items, next);
    }

    /** Settles by id in the given order, with their receipts, as {@code viewer} sees them. */
    List<SettleView> views(Collection<UUID> ids, UUID viewer, OffsetDateTime now) {
        if (ids.isEmpty()) return List.of();
        String array = "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
        Map<UUID, List<SettleLine>> lines = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sl.settle_id, l.id, l.table_id, l.payer_id, l.payee_id, l.amount_cents, l.created_at
                FROM settle_lines sl JOIN settlement_lines l ON l.id = sl.line_id
                WHERE sl.settle_id = ANY (?::uuid[]) ORDER BY l.created_at DESC, l.id
                """, rs -> {
                    lines.computeIfAbsent(rs.getObject("settle_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new SettleLine(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                                    rs.getObject("payer_id", UUID.class), rs.getObject("payee_id", UUID.class),
                                    rs.getLong("amount_cents"), rs.getObject("created_at", OffsetDateTime.class)));
                }, array);
        Map<UUID, SettleView> byId = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM settles WHERE id = ANY (?::uuid[])", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            String kind = rs.getString("kind");
            UUID otherId = rs.getObject("other_id", UUID.class);
            OffsetDateTime rejectBy = rs.getObject("reject_by", OffsetDateTime.class);
            OffsetDateTime rejectedAt = rs.getObject("rejected_at", OffsetDateTime.class);
            byId.put(id, new SettleView(id, kind, rs.getObject("sender_id", UUID.class), otherId,
                    rs.getObject("payer_id", UUID.class), rs.getObject("payee_id", UUID.class),
                    rs.getLong("amount_cents"), rs.getObject("created_at", OffsetDateTime.class), rejectBy,
                    rejectedAt, otherId.equals(viewer) && SettleRules.rejectable(kind, rejectBy, rejectedAt, now),
                    List.copyOf(lines.getOrDefault(id, List.of()))));
        }, array);
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private boolean hasReceipts(UUID actor, UUID other) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM settlement_lines
                    WHERE (payer_id = ? AND payee_id = ?) OR (payer_id = ? AND payee_id = ?))
                """, Boolean.class, actor, other, other, actor));
    }

    private OffsetDateTime now() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class));
    }

    private void changed(UUID actor, UUID other) {
        outbox.append(OutboxEvent.SETTLEMENT_GROUP_CHANGED, actor + ":" + other,
                Map.of("userId", actor, "otherUserId", other));
    }
}
