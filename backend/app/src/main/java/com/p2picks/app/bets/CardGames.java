package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.DebtQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the card games (Jackblack, Hold'em) share: their players and seats ({@code card_players}), their stored state
 * ({@code card_games}), joining and leaving during the join window, closing it at the deal, the frame around every
 * move (authentication, idempotency, a lapsed timer applied first), and paying out the final totals through the
 * ledger. Each mode's service supplies only its game: its rules, its moves and what each viewer may see.
 *
 * <p>Locks: every write that can touch a game locks its players in id order, then the table, then the bet and its
 * game, the same order other bet writes use. Joining or leaving locks only that player, then the table and the bet.
 */
@Component
class CardGames {
    public enum Outcome { DONE, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    /** Something the game's state doesn't allow right now (409); the message is shown to the player. */
    static class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    /** The player's points limit can't cover the game's worst case (409). */
    static class DebtCapExceeded extends RuntimeException {}

    /** Players changed between reading and locking them; the caller retries. */
    static class PlayersChanged extends RuntimeException {}

    /** The wash reason when too few players are left at the deal. */
    static final String NOT_ENOUGH = "Not enough players";

    /** A card game's state as the shared plumbing sees it. */
    interface State {
        boolean over();

        /** The next timer (epoch millis), or null when nothing is waiting. */
        Long nextDeadline();

        /** Each player's final result in cents (zero-sum), once {@link #over()}. */
        Map<UUID, Long> results();
    }

    /** A card game's bet row, locked. {@code open}: the join window hasn't closed. */
    record Bet(UUID id, UUID tableId, UUID proposerId, String status, boolean forFun, long wagerCents,
            JsonNode config, boolean open) {}

    /** What a game's worst case holds against each player's points limit with {@code players} in it. */
    @FunctionalInterface
    interface Risk {
        long cents(Bet bet, int players);
    }

    /** Applies a lapsed timer to a game, if there is one; returns whether there was. */
    @FunctionalInterface
    interface Timeout<G extends State> {
        boolean apply(Bet bet, G game, long now);
    }

    /** A player's move in a game; throws {@link NotAllowed} when the rules refuse it. */
    @FunctionalInterface
    interface Move<G extends State> {
        void apply(Bet bet, G game, UUID userId, long now);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;
    private final BetResolutionWriter resolutions;

    CardGames(JdbcTemplate jdbc, ObjectMapper json, MutationAuthentication authentication,
            IdempotencyGuard idempotency, DebtQuery debt, Outbox outbox, BetResolutionWriter resolutions) {
        this.jdbc = jdbc;
        this.json = json;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
        this.outbox = outbox;
        this.resolutions = resolutions;
    }

    // ----- Seats -----

    /** Seats the proposer in their own new game, holding {@code riskCents}. */
    @Transactional(propagation = Propagation.MANDATORY)
    void seatProposer(UUID betId, UUID userId, long riskCents) {
        jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", betId, userId);
        jdbc.update("INSERT INTO card_players (bet_id, user_id, risk_cents) VALUES (?, ?, ?)", betId, userId,
                riskCents);
    }

    /**
     * Joins or leaves a game while its join window is open, within the proposer's player range; the proposer can't
     * leave. Everyone's held risk is repriced for the new count, and a joiner's has to fit their points limit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Outcome seat(String mode, Jwt jwt, UUID betId, boolean joining, String key, Risk risk) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId))) {
            return Outcome.FORBIDDEN;
        }
        Bet bet = lockTableAndBet(mode, betId);
        if (bet == null) return Outcome.NOT_FOUND;
        List<Boolean> member = jdbc.query("""
                SELECT can_bet FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getBoolean("can_bet"), bet.tableId(), userId);
        authentication.requireUnexpired(jwt);
        if (member.isEmpty()) return Outcome.NOT_FOUND;
        if (!member.getFirst()) return Outcome.FORBIDDEN;
        String operation = "bet." + mode + (joining ? ".join.v1" : ".leave.v1");
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation, betId.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("ACTIVE") || !bet.open()) throw new Refused("The cards are already out.");

        List<UUID> players = players(betId);
        boolean in = players.contains(userId);
        int changed = 0;
        if (!joining && in) {
            if (bet.proposerId().equals(userId)) throw new Refused("You proposed this one.");
            changed = jdbc.update("DELETE FROM card_players WHERE bet_id = ? AND user_id = ?", betId, userId);
            reprice(bet, risk, players.size() - 1);
        } else if (joining && !in) {
            if (players.size() >= bet.config().path("maxPlayers").asInt()) throw new Refused("The game is full.");
            long cents = bet.forFun() ? 0 : risk.cents(bet, players.size() + 1);
            if (!debt.canRiskForLockedUser(userId, cents, bet.forFun())) throw new DebtCapExceeded();
            jdbc.update("""
                    INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)
                    ON CONFLICT (bet_id, user_id) DO NOTHING
                    """, betId, userId);
            changed = jdbc.update("INSERT INTO card_players (bet_id, user_id, risk_cents) VALUES (?, ?, ?)",
                    betId, userId, cents);
            reprice(bet, risk, players.size() + 1);
        }
        if (changed != 0) changed(bet.tableId(), betId);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    /** Players in joining order. */
    List<UUID> players(UUID betId) {
        return jdbc.queryForList("""
                SELECT user_id FROM card_players WHERE bet_id = ? ORDER BY joined_at, user_id
                """, UUID.class, betId);
    }

    // ----- Deal -----

    /**
     * Locks a game whose join window has closed for its deal, or null when it isn't due or is already dealt. Throws
     * {@link PlayersChanged} if someone joined or left while its players were being locked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Bet lockForDeal(String mode, UUID betId) {
        List<UUID> joined = players(betId);
        Bet bet = lockGame(mode, betId, joined);
        if (bet == null || !bet.status().equals("ACTIVE") || bet.open()) return null;
        if (!new HashSet<>(joined).equals(new HashSet<>(players(betId)))) throw new PlayersChanged();
        return bet;
    }

    /**
     * The players dealt in, in joining order: anyone who can no longer bet at the table is dropped, and then (unless
     * it's 4fun) anyone who can't cover the worst case for the final count, latest joiner first, which lowers it for
     * the rest. Everyone left is repriced for the final count.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<UUID> dealIn(Bet bet, Risk risk, int minPlayers) {
        List<UUID> players = new ArrayList<>();
        for (UUID userId : players(bet.id())) {
            boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users u JOIN table_members m ON m.user_id = u.id AND m.table_id = ?
                        WHERE u.id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER' AND m.can_bet)
                    """, Boolean.class, bet.tableId(), userId));
            if (eligible) players.add(userId);
            else drop(bet.id(), userId);
        }
        if (!bet.forFun()) {
            boolean dropped = true;
            while (dropped && players.size() >= minPlayers) {
                dropped = false;
                long cents = risk.cents(bet, players.size());
                for (int i = players.size() - 1; i >= 0; i--) {
                    if (!debt.canRiskForLockedUser(players.get(i), cents, false)) {
                        drop(bet.id(), players.remove(i));
                        dropped = true;
                        break;
                    }
                }
            }
        }
        if (players.size() >= minPlayers) reprice(bet, risk, players.size());
        return players;
    }

    /**
     * Closes joining (the bet goes Pending) and, with at least {@code minPlayers}, stores the new game; with fewer it
     * washes ("Not enough players") and stores nothing. Returns whether the game started.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean start(Bet bet, int players, int minPlayers, LongFunction<State> deal) {
        if (jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                """, bet.id()) != 1) {
            throw new IllegalStateException("Card game " + bet.id() + " changed while dealing");
        }
        outbox.append(OutboxEvent.BET_LOCKED, bet.id(), Map.of("tableId", bet.tableId(), "betId", bet.id()));
        if (players < minPlayers) {
            resolutions.apply(new BetResolutionWriter.LockedBet(bet.id(), bet.tableId(), 0, bet.forFun(), List.of(),
                    List.of()), null, NOT_ENOUGH, null, false);
            return false;
        }
        State game = deal.apply(now());
        jdbc.update("INSERT INTO card_games (bet_id, state, next_deadline) VALUES (?, ?::jsonb, ?)",
                bet.id(), write(game), deadline(game));
        save(bet, game);
        return true;
    }

    // ----- Moves -----

    /**
     * The frame around a player's move: only the game's players, an idempotent tap, a game that's dealt and not
     * over, and a lapsed timer applied instead of the tap (so a late tap can't land on the next turn). The rules'
     * own refusals ({@link NotAllowed}) reach the player; any other failure stays a server error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    <G extends State> Outcome act(String mode, Class<G> type, Jwt jwt, UUID betId, String key, String request,
            Timeout<G> timeout, Move<G> move) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID userId = UUID.fromString(jwt.getSubject());
        List<UUID> players = players(betId);
        if (!players.contains(userId)) {
            return jdbc.queryForList("SELECT id FROM bets WHERE id = ?", UUID.class, betId).isEmpty()
                    ? Outcome.NOT_FOUND : Outcome.FORBIDDEN;
        }
        Bet bet = lockGame(mode, betId, players);
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (bet == null) return Outcome.NOT_FOUND;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, "bet." + mode + ".act.v1",
                betId + "|" + request);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("PENDING")) throw new Refused("This game is over.");
        G game = load(betId, type);
        if (game == null) throw new Refused("The cards aren't out yet.");
        long now = now();
        if (!timeout.apply(bet, game, now)) {
            try {
                move.apply(bet, game, userId, now);
            } catch (NotAllowed refused) {
                throw new Refused(refused.getMessage());
            }
        }
        save(bet, game);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    /** Applies a lapsed timer (the worker). Returns whether the game moved on. */
    @Transactional(propagation = Propagation.MANDATORY)
    <G extends State> boolean expire(String mode, Class<G> type, UUID betId, Timeout<G> timeout) {
        Bet bet = lockGame(mode, betId, players(betId));
        if (bet == null || !bet.status().equals("PENDING")) return false;
        G game = load(betId, type);
        if (game == null || !timeout.apply(bet, game, now())) return false;
        save(bet, game);
        return true;
    }

    // ----- State -----

    /** The game's stored state, or null before the deal. */
    <G extends State> G load(UUID betId, Class<G> type) {
        List<String> states = jdbc.queryForList("SELECT state::text FROM card_games WHERE bet_id = ?",
                String.class, betId);
        if (states.isEmpty()) return null;
        try {
            return json.readValue(states.getFirst(), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable card game " + betId, exception);
        }
    }

    /** The bet's stored config (its rules), read without locking. */
    JsonNode config(UUID betId) {
        return read(jdbc.queryForObject("SELECT config::text FROM bets WHERE id = ?", String.class, betId));
    }

    /** Stores the game, tells the table, and pays it out once it's over. */
    private void save(Bet bet, State game) {
        jdbc.update("UPDATE card_games SET state = ?::jsonb, next_deadline = ? WHERE bet_id = ?",
                write(game), deadline(game), bet.id());
        jdbc.update("UPDATE bets SET version = version + 1 WHERE id = ?", bet.id());
        changed(bet.tableId(), bet.id());
        if (game.over()) settle(bet, game.results());
    }

    /** Pays the finished game: each player's result, zero-sum (nothing for 4fun). */
    private void settle(Bet bet, Map<UUID, Long> results) {
        if (jdbc.update("""
                UPDATE bets SET status = 'SETTLED', closed_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'PENDING'
                """, bet.id()) != 1) {
            throw new IllegalStateException("Card game " + bet.id() + " changed while paying out");
        }
        if (!bet.forFun()) {
            for (Map.Entry<UUID, Long> entry : results.entrySet()) {
                if (entry.getValue() == 0) continue;
                jdbc.update("""
                        INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), bet.tableId(), bet.id(), entry.getKey(), entry.getValue());
            }
        }
        jdbc.update("UPDATE card_games SET next_deadline = NULL WHERE bet_id = ?", bet.id());
        outbox.append(OutboxEvent.BET_RESOLVED, bet.id(), Map.of("tableId", bet.tableId(), "betId", bet.id()));
    }

    // ----- Locks -----

    /** Locks the given players in id order, then the table, the bet and its game. Null if the bet is gone. */
    private Bet lockGame(String mode, UUID betId, List<UUID> players) {
        String ids = "{" + String.join(",", players.stream().map(UUID::toString).toList()) + "}";
        jdbc.query("SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), ids);
        return lockTableAndBet(mode, betId);
    }

    private Bet lockTableAndBet(String mode, UUID betId) {
        List<UUID> tables = jdbc.queryForList("SELECT table_id FROM bets WHERE id = ? AND mode = ?", UUID.class,
                betId, mode);
        if (tables.isEmpty()) return null;
        if (jdbc.queryForList("SELECT id FROM tables WHERE id = ? AND archived_at IS NULL FOR UPDATE", UUID.class,
                tables.getFirst()).isEmpty()) {
            return null;
        }
        List<Bet> bets = jdbc.query("""
                SELECT id, table_id, proposer_id, status, is_4fun, wager_cents, config,
                       locks_at > clock_timestamp() AS open
                FROM bets WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Bet(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getObject("proposer_id", UUID.class), rs.getString("status"), rs.getBoolean("is_4fun"),
                        rs.getLong("wager_cents"), read(rs.getString("config")), rs.getBoolean("open")), betId);
        if (bets.isEmpty()) return null;
        jdbc.queryForList("SELECT bet_id FROM card_games WHERE bet_id = ? FOR UPDATE", UUID.class, betId);
        return bets.getFirst();
    }

    // ----- Internals -----

    /** Everyone's held worst case for {@code count} players (re-checked against their limits at the deal). */
    private void reprice(Bet bet, Risk risk, int count) {
        long cents = bet.forFun() ? 0 : risk.cents(bet, Math.max(count, 2));
        jdbc.update("UPDATE card_players SET risk_cents = ? WHERE bet_id = ?", cents, bet.id());
    }

    private void drop(UUID betId, UUID userId) {
        jdbc.update("DELETE FROM card_players WHERE bet_id = ? AND user_id = ?", betId, userId);
    }

    private void changed(UUID tableId, UUID betId) {
        outbox.append(OutboxEvent.BET_CARD_GAME_CHANGED, betId, Map.of("tableId", tableId, "betId", betId));
    }

    /** The database's clock, so every timer agrees with {@code locks_at}. */
    private long now() {
        Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (now == null) throw new IllegalStateException("Database clock is unavailable");
        return now.getTime();
    }

    private static Timestamp deadline(State game) {
        Long next = game.nextDeadline();
        return next == null ? null : new Timestamp(next);
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
