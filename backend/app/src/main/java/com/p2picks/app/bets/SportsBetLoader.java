package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a bet (sports, or a U2Pick for {@link U2PickExpiry}) for resolution or override after the caller has locked the users it affects
 * ({@link #affectedUsers}), then locks the table and the bet, in the repository's users-then-table-then-bet order.
 */
@Component
class SportsBetLoader {
    record Loaded(BetResolutionWriter.LockedBet bet, String mode, String gameId, String status, boolean pointsSettled,
                  SportsOutcome.Terms terms, UUID proposerId) {}

    static class UsersChanged extends RuntimeException {
        UsersChanged() {
            super("Affected users changed while acquiring locks; retry the transaction");
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    SportsBetLoader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A sports bet's game and terms read without locks, to decide whether locking is worthwhile; null if absent. */
    Peek peek(UUID betId) {
        List<Peek> peeks = jdbc.query("""
                SELECT mode, game_id, status, choices::text, config::text, includes_overtime, locked_at
                FROM bets WHERE id = ?
                """, (rs, n) -> new Peek(rs.getString("game_id"), rs.getString("status"),
                        terms(rs.getString("mode"), rs.getString("config"), rs.getObject("includes_overtime"),
                                strings(rs.getString("choices")), rs.getTimestamp("locked_at"))), betId);
        return peeks.isEmpty() ? null : peeks.getFirst();
    }

    record Peek(String gameId, String status, SportsOutcome.Terms terms) {}

    /** Table members, participants and the proposer; null when the bet doesn't exist. */
    List<UUID> affectedUsers(UUID betId) {
        List<UUID> tables = jdbc.query("SELECT table_id FROM bets WHERE id = ?",
                (rs, row) -> rs.getObject("table_id", UUID.class), betId);
        if (tables.isEmpty()) return null;
        return jdbc.query("""
                SELECT user_id FROM table_members WHERE table_id = ?
                UNION SELECT user_id FROM bet_participants WHERE bet_id = ?
                UNION SELECT proposer_id FROM bets WHERE id = ?
                """, (rs, row) -> rs.getObject(1, UUID.class), tables.getFirst(), betId, betId);
    }

    /**
     * Locks the table and bet and loads it; {@code lockedUsers} must hold every affected user. Returns null for an
     * archived table.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Loaded load(UUID betId, Set<UUID> lockedUsers) {
        UUID tableId = jdbc.queryForObject("SELECT table_id FROM bets WHERE id = ?", UUID.class, betId);
        if (jdbc.query("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), tableId).isEmpty()) {
            return null;
        }
        Row row = jdbc.queryForObject("""
                SELECT mode, game_id, status, points_settled_at IS NOT NULL AS settled, choices::text, config::text,
                       wager_cents, is_4fun, includes_overtime, proposer_id,
                       locked_at
                FROM bets WHERE id = ? AND table_id = ? FOR UPDATE
                """, (rs, n) -> new Row(rs.getString("mode"), rs.getString("game_id"), rs.getString("status"),
                rs.getBoolean("settled"), rs.getString("choices"), rs.getString("config"), rs.getLong("wager_cents"),
                rs.getBoolean("is_4fun"), (Boolean) rs.getObject("includes_overtime"),
                rs.getObject("proposer_id", UUID.class), rs.getTimestamp("locked_at")), betId, tableId);
        List<PayoutCalculator.Participant> participants = jdbc.query("""
                SELECT bp.user_id, p.choice FROM bet_participants bp
                LEFT JOIN picks p ON p.bet_id = bp.bet_id AND p.user_id = bp.user_id
                WHERE bp.bet_id = ? ORDER BY bp.participation_order
                """, (rs, n) -> new PayoutCalculator.Participant(rs.getObject("user_id", UUID.class),
                rs.getString("choice")), betId);
        List<UUID> members = jdbc.query("SELECT user_id FROM table_members WHERE table_id = ?",
                (rs, n) -> rs.getObject("user_id", UUID.class), tableId);
        if (members.stream().anyMatch(id -> !lockedUsers.contains(id))
                || participants.stream().anyMatch(p -> !lockedUsers.contains(p.userId()))
                || !lockedUsers.contains(row.proposerId())) {
            throw new UsersChanged();
        }
        List<String> choices = strings(row.choices());
        return new Loaded(new BetResolutionWriter.LockedBet(betId, tableId, row.wagerCents(), row.forFun(), choices,
                participants), row.mode(), row.gameId(), row.status(), row.settled(),
                terms(row.mode(), row.config(), row.includesOvertime(), choices, row.lockedAt()), row.proposerId());
    }

    /** A bet's terms from its stored columns: the line (or target), stat and players live in {@code config}. */
    private SportsOutcome.Terms terms(String mode, String config, Object includesOvertime, List<String> choices,
            java.sql.Timestamp lockedAt) {
        JsonNode node = read(config);
        List<String> players = new ArrayList<>();
        for (JsonNode player : node.path("players")) players.add(player.textValue());
        return new SportsOutcome.Terms(mode, node.has("line") ? node.path("line").decimalValue() : null,
                !Boolean.FALSE.equals(includesOvertime), choices,
                node.has("stat") ? node.path("stat").textValue() : null, List.copyOf(players),
                lockedAt == null ? null : lockedAt.toInstant());
    }

    private List<String> strings(String serialized) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : read(serialized)) values.add(value.textValue());
        return List.copyOf(values);
    }

    private JsonNode read(String serialized) {
        try {
            return json.readTree(serialized);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read bet", exception);
        }
    }

    private record Row(String mode, String gameId, String status, boolean settled, String choices, String config,
                       long wagerCents, boolean forFun, Boolean includesOvertime,
                       UUID proposerId, java.sql.Timestamp lockedAt) {}
}
