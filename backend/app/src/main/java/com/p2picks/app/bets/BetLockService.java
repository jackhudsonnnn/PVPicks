package com.p2picks.app.bets;

import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.settlement.DebtQuery;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BetLockService {
    public enum Outcome { LOCKED, NOT_DUE, ALREADY_LOCKED, NOT_FOUND }

    static class ParticipantsChanged extends RuntimeException {
        ParticipantsChanged() {
            super("Pick participants changed while acquiring locks; retry the transaction");
        }
    }

    static class UnsupportedMode extends RuntimeException {
        UnsupportedMode(String mode) {
            super("Automatic locking is not enabled for mode " + mode);
        }
    }

    private final JdbcTemplate jdbc;
    private final DebtQuery debt;
    private final SecureRandom random = new SecureRandom();
    private final Outbox outbox;
    private final BetResolutionWriter writer;
    private final ObjectProvider<LockWashRule> modeRules;

    BetLockService(JdbcTemplate jdbc, DebtQuery debt, Outbox outbox, BetResolutionWriter writer,
            ObjectProvider<LockWashRule> modeRules) {
        this.jdbc = jdbc;
        this.debt = debt;
        this.outbox = outbox;
        this.writer = writer;
        this.modeRules = modeRules;
    }

    /** General lock washes, for every mode. */
    static final String NOBODY_PICKED = "Nobody picked";
    static final String ONE_OPTION = "Everyone picked the same option";

    @Transactional
    public Outcome lock(UUID betId) {
        return lockInternal(betId, null, false);
    }

    @Transactional
    public Outcome lock(UUID betId, Set<String> supportedModes) {
        return lockInternal(betId, Set.copyOf(supportedModes), false);
    }

    /** Locks before the deadline, for a host override on a bet still taking picks. */
    @Transactional
    public Outcome lockNow(UUID betId) {
        return lockInternal(betId, null, true);
    }

    private Outcome lockInternal(UUID betId, Set<String> supportedModes, boolean early) {
        List<UUID> tableIds = jdbc.query("SELECT table_id FROM bets WHERE id = ?",
                (rs, row) -> rs.getObject("table_id", UUID.class), betId);
        if (tableIds.isEmpty()) return Outcome.NOT_FOUND;
        UUID tableId = tableIds.getFirst();
        List<UUID> initialPickers = jdbc.query("SELECT user_id FROM picks WHERE bet_id = ?",
                (rs, row) -> rs.getObject("user_id", UUID.class), betId);
        if (!initialPickers.isEmpty()) {
            String ids = "{" + String.join(",", initialPickers.stream().map(UUID::toString).toList()) + "}";
            List<UUID> locked = jdbc.query("""
                    SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE
                    """, (rs, row) -> rs.getObject("id", UUID.class), ids);
            if (locked.size() != initialPickers.size()) throw new IllegalStateException("Pick user missing");
        }
        List<String> rules = jdbc.query("""
                SELECT remainder_rule FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, (rs, row) -> rs.getString("remainder_rule"), tableId);
        if (rules.isEmpty()) throw new IllegalStateException("Bet table is missing or archived");
        List<Bet> bets = jdbc.query("""
                SELECT status, mode, locks_at <= clock_timestamp() AS due, wager_cents, is_4fun
                FROM bets WHERE id = ? AND table_id = ? FOR UPDATE
                """, (rs, row) -> new Bet(rs.getString("status"), rs.getString("mode"), rs.getBoolean("due"),
                        rs.getLong("wager_cents"), rs.getBoolean("is_4fun")), betId, tableId);
        if (bets.isEmpty()) throw new IllegalStateException("Bet disappeared during locking");
        Bet bet = bets.getFirst();
        if (!bet.status().equals("ACTIVE")) return Outcome.ALREADY_LOCKED;
        if (!bet.due() && !early) return Outcome.NOT_DUE;
        if (supportedModes != null && !supportedModes.contains(bet.mode())) throw new UnsupportedMode(bet.mode());

        List<Picker> pickers = jdbc.query("""
                SELECT p.user_id, u.status, u.role, m.can_bet
                FROM picks p JOIN users u ON u.id = p.user_id
                LEFT JOIN table_members m ON m.table_id = ? AND m.user_id = p.user_id
                WHERE p.bet_id = ?
                """, (rs, row) -> new Picker(rs.getObject("user_id", UUID.class),
                        rs.getString("status"), rs.getString("role"),
                        rs.getObject("can_bet", Boolean.class)),
                tableId, betId);
        Set<UUID> lockedUsers = new HashSet<>(initialPickers);
        if (pickers.stream().anyMatch(picker -> !lockedUsers.contains(picker.id()))) {
            throw new ParticipantsChanged();
        }
        for (Picker picker : pickers) {
            boolean eligible = picker.status().equals("ACTIVE") && picker.role().equals("PLAYER")
                    && Boolean.TRUE.equals(picker.canBet());
            if (eligible) eligible = debt.canRiskForLockedUser(picker.id(), bet.wager(), bet.forFun());
            if (!eligible && jdbc.update("DELETE FROM picks WHERE bet_id = ? AND user_id = ?", betId, picker.id()) != 1) {
                throw new IllegalStateException("Locked pick disappeared");
            }
        }
        String rule = rules.getFirst();
        UUID host = null;
        if (rule.equals("host")) {
            List<UUID> hosts = jdbc.query("""
                    SELECT user_id FROM table_members WHERE table_id = ? AND role = 'HOST' ORDER BY user_id
                    """, (rs, row) -> rs.getObject("user_id", UUID.class), tableId);
            if (hosts.isEmpty()) throw new IllegalStateException("Host remainder has no current host");
            host = hosts.get(random.nextInt(hosts.size()));
        }
        if (jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = clock_timestamp(), remainder_rule = ?,
                    remainder_host_id = ?, version = version + 1 WHERE id = ? AND status = 'ACTIVE'
                """, rule, host, betId) != 1) {
            throw new IllegalStateException("Locked bet changed");
        }
        outbox.append(OutboxEvent.BET_LOCKED, betId, Map.of("tableId", tableId, "betId", betId));
        // A bet nobody can win or lose washes right away rather than waiting on a result (domain rules, "Wash
        // triggers"): no picks, or every pick on one option. Then any mode-specific lock rule.
        String wash = generalLockWash(betId);
        if (wash == null) {
            wash = modeRules.orderedStream().map(modeRule -> modeRule.washAtLock(betId, bet.mode()))
                    .flatMap(java.util.Optional::stream).findFirst().orElse(null);
        }
        if (wash != null) {
            writer.apply(new BetResolutionWriter.LockedBet(betId, tableId, bet.wager(), bet.forFun(), List.of(), null,
                    List.of()), null, wash, null, false);
        }
        return Outcome.LOCKED;
    }

    private String generalLockWash(UUID betId) {
        List<String> choices = jdbc.queryForList("SELECT DISTINCT choice FROM picks WHERE bet_id = ?", String.class,
                betId);
        if (choices.isEmpty()) return NOBODY_PICKED;
        return choices.size() == 1 ? ONE_OPTION : null;
    }

    private record Bet(String status, String mode, boolean due, long wager, boolean forFun) {}

    private record Picker(UUID id, String status, String role, Boolean canBet) {}
}
