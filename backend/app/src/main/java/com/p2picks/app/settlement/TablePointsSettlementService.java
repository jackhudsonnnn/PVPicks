package com.p2picks.app.settlement;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.TableSettlementCalculator.ExactBetDeltas;
import com.p2picks.app.settlement.TableSettlementCalculator.Line;
import com.p2picks.app.settlement.TableSettlementCalculator.Plan;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TablePointsSettlementService {
    public enum Outcome { CREATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }
    public record Result(Outcome outcome, UUID settlementId) {}
    record Settlement(UUID id, UUID tableId, UUID hostId, OffsetDateTime createdAt,
                      List<SettlementReceiptService.Line> lines) {}
    record ReadResult(boolean authenticated, Settlement settlement) {}

    static class NoEligibleBets extends RuntimeException {}
    static class AffectedUsersChanged extends RuntimeException {
        AffectedUsersChanged() {
            super("Affected users changed while acquiring locks; retry the transaction");
        }
    }

    private static final String OPERATION = "table.points-settlement.v1";
    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final SettlementNetReconciliation reconciliation;
    private final Outbox outbox;

    TablePointsSettlementService(JdbcTemplate jdbc, MutationAuthentication authentication,
            IdempotencyGuard idempotency, SettlementNetReconciliation reconciliation, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
    }

    @Transactional
    public Result settle(Jwt jwt, UUID tableId, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID actor = UUID.fromString(jwt.getSubject());
        List<UUID> hints = jdbc.query("""
                SELECT DISTINCT e.user_id FROM ledger_entries e JOIN bets b ON b.id = e.bet_id
                WHERE b.table_id = ? AND b.status = 'SETTLED'
                  AND NOT b.is_4fun AND b.points_settled_at IS NULL
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tableId);
        Map<UUID, String> lockedUsers = authentication.lockActiveWithTargets(jwt, hints);
        if (lockedUsers == null) return new Result(Outcome.UNAUTHORIZED, null);
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor))) {
            return new Result(Outcome.FORBIDDEN, null);
        }
        if (jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId).isEmpty()) {
            authentication.requireUnexpired(jwt);
            return new Result(Outcome.NOT_FOUND, null);
        }
        List<String> roles = jdbc.query("SELECT role FROM table_members WHERE table_id = ? AND user_id = ?",
                (rs, row) -> rs.getString("role"), tableId, actor);
        authentication.requireUnexpired(jwt);
        if (roles.isEmpty()) return new Result(Outcome.NOT_FOUND, null);
        if (!roles.getFirst().equals("HOST")) return new Result(Outcome.FORBIDDEN, null);
        IdempotencyGuard.Claim claim = idempotency.claim(actor, key, OPERATION, tableId.toString(),
                IdempotencyGuard.SuccessStatus.CREATED);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return new Result(Outcome.CONFLICT, null);
        if (claim == IdempotencyGuard.Claim.REPLAY) {
            return new Result(Outcome.REPLAYED, idempotency.responseReference(actor, key, OPERATION));
        }

        List<UUID> betIds = jdbc.query("""
                SELECT id FROM bets WHERE table_id = ? AND status = 'SETTLED'
                  AND NOT is_4fun AND points_settled_at IS NULL ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (betIds.isEmpty()) throw new NoEligibleBets();
        Map<UUID, Map<UUID, BigInteger>> deltas = new LinkedHashMap<>();
        for (UUID betId : betIds) deltas.put(betId, new HashMap<>());
        jdbc.query("""
                SELECT e.bet_id, e.user_id, sum(e.delta_cents) AS delta
                FROM ledger_entries e JOIN bets b ON b.id = e.bet_id
                WHERE b.table_id = ? AND b.status = 'SETTLED'
                  AND NOT b.is_4fun AND b.points_settled_at IS NULL
                GROUP BY e.bet_id, e.user_id
                """, rs -> {
                    UUID userId = rs.getObject("user_id", UUID.class);
                    if (!lockedUsers.containsKey(userId)) throw new AffectedUsersChanged();
                    Map<UUID, BigInteger> bet = deltas.get(rs.getObject("bet_id", UUID.class));
                    if (bet == null) throw new IllegalStateException("Eligible bet changed while table was locked");
                    bet.put(userId, rs.getBigDecimal("delta").toBigIntegerExact());
                }, tableId);
        List<ExactBetDeltas> input = new ArrayList<>();
        deltas.forEach((betId, values) -> input.add(new ExactBetDeltas(betId, values)));
        Plan plan = TableSettlementCalculator.calculateExact(actor, input);

        UUID settlementId = UUID.randomUUID();
        jdbc.update("INSERT INTO settlements (id, table_id, host_id) VALUES (?, ?, ?)", settlementId, tableId, actor);
        for (UUID betId : plan.betIds()) {
            jdbc.update("INSERT INTO settlement_bets (bet_id, table_id, settlement_id) VALUES (?, ?, ?)",
                    betId, tableId, settlementId);
            int updated = jdbc.update("""
                    UPDATE bets SET points_settled_at = (SELECT created_at FROM settlements WHERE id = ?),
                        version = version + 1
                    WHERE id = ? AND table_id = ? AND status = 'SETTLED'
                      AND NOT is_4fun AND points_settled_at IS NULL
                    """, settlementId, betId, tableId);
            if (updated != 1) throw new IllegalStateException("Eligible bet changed during settlement");
        }
        for (Line line : plan.lines()) {
            UUID other = line.payerId().equals(actor) ? line.payeeId() : line.payerId();
            SettlementNetReconciliation.Snapshot before = reconciliation.capture(actor, other);
            jdbc.update("""
                    INSERT INTO settlement_lines
                        (id, settlement_id, table_id, host_id, payer_id, payee_id, amount_cents)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), settlementId, tableId, actor, line.payerId(), line.payeeId(),
                    line.amount().cents());
            reconciliation.reconcile(before);
        }
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_messages (id, table_id, kind, settlement_id) VALUES (?, ?, 'settlement', ?)",
                messageId, tableId, settlementId);
        outbox.append(OutboxEvent.SETTLEMENT_CREATED, settlementId,
                Map.of("tableId", tableId, "settlementId", settlementId));
        outbox.append(OutboxEvent.CHAT_MESSAGE_CREATED, tableId,
                Map.of("tableId", tableId, "messageId", messageId, "kind", "settlement"));
        idempotency.saveResponseReference(actor, key, OPERATION, settlementId);
        authentication.requireUnexpired(jwt);
        return new Result(Outcome.CREATED, settlementId);
    }

    ReadResult readForHost(Jwt jwt, UUID settlementId) {
        return jdbc.query("""
                WITH viewer AS (
                    SELECT u.id FROM users u JOIN auth_sessions s ON s.user_id = u.id
                    WHERE u.id = ? AND s.id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER'
                      AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
                )
                SELECT s.id AS result_id, s.table_id AS result_table_id, s.host_id AS result_host_id,
                       s.created_at AS result_created_at, l.*
                FROM viewer
                LEFT JOIN settlements s ON s.id = ? AND s.host_id = viewer.id
                    AND EXISTS (SELECT 1 FROM tables t JOIN table_members m ON m.table_id = t.id
                                WHERE t.id = s.table_id AND t.archived_at IS NULL
                                  AND m.user_id = viewer.id AND m.role = 'HOST')
                LEFT JOIN settlement_lines l ON l.settlement_id = s.id
                ORDER BY l.created_at, l.id
                """, rs -> {
                    if (!rs.next()) return new ReadResult(false, null);
                    UUID id = rs.getObject("result_id", UUID.class);
                    if (id == null) return new ReadResult(true, null);
                    UUID tableId = rs.getObject("result_table_id", UUID.class);
                    UUID hostId = rs.getObject("result_host_id", UUID.class);
                    OffsetDateTime createdAt = rs.getObject("result_created_at", OffsetDateTime.class);
                    List<SettlementReceiptService.Line> lines = new ArrayList<>();
                    do {
                        if (rs.getObject("id", UUID.class) != null) lines.add(SettlementReceiptService.line(rs));
                    } while (rs.next());
                    return new ReadResult(true, new Settlement(id, tableId, hostId, createdAt, List.copyOf(lines)));
                }, UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getId()), settlementId);
    }
}
