package com.p2picks.app.bets;

import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.common.PostgresText;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.DebtQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BetPickService {
    enum Outcome { UPDATED, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    static class Closed extends RuntimeException {}
    static class InvalidChoice extends RuntimeException {}
    static class DebtCapExceeded extends RuntimeException {}

    private final JdbcTemplate jdbc;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;

    BetPickService(JdbcTemplate jdbc, MutationAuthentication authentication, IdempotencyGuard idempotency,
            DebtQuery debt, Outbox outbox) {
        this.jdbc = jdbc;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
        this.outbox = outbox;
    }

    // A null choice clears a pick; it never creates participation.
    @Transactional
    public Outcome change(Jwt jwt, UUID betId, String choice, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (choice != null && (!PostgresText.valid(choice) || PostgresText.trim(choice).isEmpty())) {
            throw new InvalidChoice();
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId);
        if (!"PLAYER".equals(role)) return Outcome.FORBIDDEN;

        List<UUID> tableIds = jdbc.query("SELECT table_id FROM bets WHERE id = ?",
                (rs, row) -> rs.getObject("table_id", UUID.class), betId);
        if (tableIds.isEmpty()) return Outcome.NOT_FOUND;
        UUID tableId = tableIds.getFirst();
        List<UUID> tables = jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId);
        if (tables.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        List<Boolean> permissions = jdbc.query("""
                SELECT can_bet FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getBoolean("can_bet"), tableId, userId);
        if (permissions.isEmpty()) {
            authentication.requireUnexpired(jwt);
            return Outcome.NOT_FOUND;
        }
        if (!permissions.getFirst()) {
            authentication.requireUnexpired(jwt);
            return Outcome.FORBIDDEN;
        }
        List<Bet> bets = jdbc.query("""
                SELECT status, wager_cents, is_4fun FROM bets WHERE id = ? AND table_id = ? FOR UPDATE
                """, (rs, row) -> new Bet(rs.getString("status"), rs.getLong("wager_cents"),
                        rs.getBoolean("is_4fun")), betId, tableId);
        if (bets.isEmpty()) throw new IllegalStateException("Bet disappeared during pick");

        String operation = choice == null ? "bet.pick.clear.v1" : "bet.pick.put.v1";
        String request = choice == null ? betId.toString() : betId + "|" + choice;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation, request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        Bet bet = bets.getFirst();
        if (!bet.status().equals("ACTIVE")) throw new Closed();
        requireBeforeDeadline(betId);
        if (choice != null) {
            Boolean valid = jdbc.queryForObject("SELECT jsonb_exists(choices, ?) FROM bets WHERE id = ?",
                    Boolean.class, choice, betId);
            if (!Boolean.TRUE.equals(valid)) throw new InvalidChoice();
            if (!debt.canRiskForLockedUser(userId, bet.wager(), bet.forFun())) throw new DebtCapExceeded();
        }

        List<String> previous = jdbc.query("SELECT choice FROM picks WHERE bet_id = ? AND user_id = ?",
                (rs, row) -> rs.getString("choice"), betId, userId);
        int changed;
        if (choice == null) {
            changed = jdbc.update("DELETE FROM picks WHERE bet_id = ? AND user_id = ?", betId, userId);
        } else {
            jdbc.update("""
                    INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)
                    ON CONFLICT (bet_id, user_id) DO NOTHING
                    """, betId, userId);
            changed = jdbc.update("""
                    INSERT INTO picks (bet_id, user_id, choice) VALUES (?, ?, ?)
                    ON CONFLICT (bet_id, user_id) DO UPDATE SET choice = EXCLUDED.choice, picked_at = clock_timestamp()
                    WHERE picks.choice IS DISTINCT FROM EXCLUDED.choice
                    """, betId, userId, choice);
        }
        requireBeforeDeadline(betId);
        if (changed != 0) {
            jdbc.update("UPDATE bets SET version = version + 1 WHERE id = ?", betId);
            if ((previous.isEmpty() && choice != null) || (!previous.isEmpty() && choice == null)) {
                outbox.append(OutboxEvent.BET_PICK_COUNT_CHANGED, betId,
                        Map.of("tableId", tableId, "betId", betId));
            }
        }
        authentication.requireUnexpired(jwt);
        return Outcome.UPDATED;
    }

    private void requireBeforeDeadline(UUID betId) {
        Boolean open = jdbc.queryForObject("SELECT locks_at > clock_timestamp() FROM bets WHERE id = ?",
                Boolean.class, betId);
        if (!Boolean.TRUE.equals(open)) throw new Closed();
    }

    private record Bet(String status, long wager, boolean forFun) {}
}
