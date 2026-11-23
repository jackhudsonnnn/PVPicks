package com.p2picks.app.bets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.p2picks.app.common.IdempotencyGuard;
import com.p2picks.app.common.Outbox;
import com.p2picks.app.common.OutboxEvent;
import com.p2picks.app.security.MutationAuthentication;
import com.p2picks.app.settlement.DebtQuery;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jackblack tickets (domain rules, "Jackblack"): propose (the proposer is the first player), join or leave while the
 * join window is open, deal when it closes, lockstep decisions, the dealer's button, timeouts, and paying out the
 * totals through the ledger.
 *
 * <p>Every player's worst case over the whole ticket is held against their points limit: checked when they join,
 * and again at the deal, where anyone who can no longer cover it is dropped, latest joiner first.
 *
 * <p>Locks: every write that can touch the game locks its players in id order, then the table, then the bet and its
 * game, the same order other bet writes use. Joining or leaving locks only that player, then the table and the bet.
 */
@Service
public class JackblackService {
    public enum Outcome { DONE, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    /** Something the game's state doesn't allow right now (409); the message is shown to the player. */
    static class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    static class DebtCapExceeded extends RuntimeException {}

    /** Players changed between reading and locking them; the caller retries. */
    static class PlayersChanged extends RuntimeException {}

    public static final String MODE = "jackblack";
    static final String NOT_ENOUGH = "Not enough players";
    private static final String PROPOSE = "bet.jackblack.propose.v1";
    private static final String JOIN = "bet.jackblack.join.v1";
    private static final String LEAVE = "bet.jackblack.leave.v1";
    private static final String ACT = "bet.jackblack.act.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;
    private final BetProposalWriter proposals;
    private final BetResolutionWriter resolutions;
    private final SecureRandom random = new SecureRandom();
    /** Where each round's shoe comes from: its secret's shuffle (tests stack it). */
    private volatile JackblackGame.Shoes shoes = JackblackGame.FROM_SECRETS;

    JackblackService(JdbcTemplate jdbc, ObjectMapper json, MutationAuthentication authentication,
            IdempotencyGuard idempotency, DebtQuery debt, Outbox outbox, BetProposalWriter proposals,
            BetResolutionWriter resolutions) {
        this.jdbc = jdbc;
        this.json = json;
        this.authentication = authentication;
        this.idempotency = idempotency;
        this.debt = debt;
        this.outbox = outbox;
        this.proposals = proposals;
        this.resolutions = resolutions;
    }

    /** Tests only: deal every round from {@code shoes}; null goes back to the secrets' shuffles. */
    public void dealFrom(JackblackGame.Shoes shoes) {
        this.shoes = shoes == null ? JackblackGame.FROM_SECRETS : shoes;
    }

    // ----- Propose -----

    /** The proposer joins their own ticket, so their worst case against one other player has to fit. */
    @Transactional
    public BetProposalWriter.Result propose(Jwt jwt, UUID tableId, JackblackRules rules, long anteCents,
            int durationSec, boolean forFun, String key) {
        String config = write(rules.toMap());
        long risk = forFun ? 0 : rules.worstCase(anteCents, rules.minPlayers());
        BetProposalWriter.Result result = proposals.create(jwt, tableId, key, PROPOSE,
                tableId + "|" + anteCents + "|" + durationSec + "|" + forFun + "|" + config,
                BetProposalWriter.Gate.JACKBLACK,
                () -> new BetProposalWriter.NewBet(MODE, null, config, "Jackblack", "[\"Up\",\"Down\"]",
                        anteCents, forFun, durationSec, null, null, null, risk));
        if (result.outcome() == BetProposalWriter.Outcome.CREATED) {
            UUID userId = UUID.fromString(jwt.getSubject());
            jdbc.update("INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)", result.betId(), userId);
            jdbc.update("INSERT INTO card_players (bet_id, user_id, risk_cents) VALUES (?, ?, ?)",
                    result.betId(), userId, risk);
        }
        return result;
    }

    // ----- Joining -----

    @Transactional
    public Outcome join(Jwt jwt, UUID betId, String key) {
        return seat(jwt, betId, true, key);
    }

    @Transactional
    public Outcome leave(Jwt jwt, UUID betId, String key) {
        return seat(jwt, betId, false, key);
    }

    private Outcome seat(Jwt jwt, UUID betId, boolean joining, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID userId = UUID.fromString(jwt.getSubject());
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (!"PLAYER".equals(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, userId))) {
            return Outcome.FORBIDDEN;
        }
        Bet bet = lockTableAndBet(betId);
        if (bet == null) return Outcome.NOT_FOUND;
        List<Boolean> member = jdbc.query("""
                SELECT can_bet FROM table_members WHERE table_id = ? AND user_id = ? FOR UPDATE
                """, (rs, row) -> rs.getBoolean("can_bet"), bet.tableId(), userId);
        authentication.requireUnexpired(jwt);
        if (member.isEmpty()) return Outcome.NOT_FOUND;
        if (!member.getFirst()) return Outcome.FORBIDDEN;
        String operation = joining ? JOIN : LEAVE;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation, betId.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("ACTIVE") || !bet.open()) throw new Refused("The cards are already out.");

        List<UUID> players = players(betId);
        boolean in = players.contains(userId);
        // No joining or leaving in the last seconds before the deal (owner decision, 2026-11-22).
        if (joining != in) {
            try {
                BetPickService.requireBeforeLateEntry(jdbc, betId);
            } catch (BetPickService.LateEntry late) {
                throw new Refused("The table's closed to joining and leaving "
                        + BetPickService.LATE_ENTRY_SECONDS + " seconds before the deal.");
            }
        }
        int changed = 0;
        if (!joining && in) {
            if (bet.proposerId().equals(userId)) throw new Refused("You proposed this one.");
            changed = jdbc.update("DELETE FROM card_players WHERE bet_id = ? AND user_id = ?", betId, userId);
            reprice(bet, players.size() - 1);
        } else if (joining && !in) {
            if (players.size() >= bet.rules().maxPlayers()) throw new Refused("The game is full.");
            long risk = bet.forFun() ? 0 : bet.rules().worstCase(bet.anteCents(), players.size() + 1);
            if (!debt.canRiskForLockedUser(userId, risk, bet.forFun())) throw new DebtCapExceeded();
            jdbc.update("""
                    INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)
                    ON CONFLICT (bet_id, user_id) DO NOTHING
                    """, betId, userId);
            changed = jdbc.update("INSERT INTO card_players (bet_id, user_id, risk_cents) VALUES (?, ?, ?)",
                    betId, userId, risk);
            reprice(bet, players.size() + 1);
        }
        if (changed != 0) changed(bet.tableId(), betId);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    /** Everyone's worst case for {@code count} players (re-checked against their limits at the deal). */
    private void reprice(Bet bet, int count) {
        long risk = bet.forFun() ? 0 : bet.rules().worstCase(bet.anteCents(), Math.max(count, 2));
        jdbc.update("UPDATE card_players SET risk_cents = ? WHERE bet_id = ?", risk, bet.id());
    }

    // ----- Deal -----

    /** Deals once the join window has closed. Returns false when it wasn't due or is already dealt. */
    @Transactional
    public boolean deal(UUID betId) {
        List<UUID> joined = players(betId);
        Bet bet = lockGame(betId, joined);
        if (bet == null || !bet.status().equals("ACTIVE") || bet.open()) return false;
        if (!new HashSet<>(joined).equals(new HashSet<>(players(betId)))) throw new PlayersChanged();
        JackblackRules rules = bet.rules();
        // Players in joining order, dropping anyone who can no longer bet here.
        List<UUID> players = new ArrayList<>();
        for (UUID userId : players(betId)) {
            boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users u JOIN table_members m ON m.user_id = u.id AND m.table_id = ?
                        WHERE u.id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER' AND m.can_bet)
                    """, Boolean.class, bet.tableId(), userId));
            if (eligible) players.add(userId);
            else drop(betId, userId);
        }
        // Everyone has to cover the worst case for this many players; whoever can't is dropped, latest first,
        // which lowers it for the rest.
        if (!bet.forFun()) {
            boolean dropped = true;
            while (dropped && players.size() >= rules.minPlayers()) {
                dropped = false;
                long risk = rules.worstCase(bet.anteCents(), players.size());
                for (int i = players.size() - 1; i >= 0; i--) {
                    if (!debt.canRiskForLockedUser(players.get(i), risk, false)) {
                        drop(betId, players.remove(i));
                        dropped = true;
                        break;
                    }
                }
            }
        }
        if (players.size() >= rules.minPlayers()) reprice(bet, players.size());
        if (jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                """, betId) != 1) {
            throw new IllegalStateException("Jackblack bet changed while dealing");
        }
        outbox.append(OutboxEvent.BET_LOCKED, betId, Map.of("tableId", bet.tableId(), "betId", betId));
        if (players.size() < rules.minPlayers()) {
            resolutions.apply(lockedBet(bet), null, NOT_ENOUGH, null, false);
            return true;
        }
        JackblackGame game = JackblackGame.start(rules, players, bet.anteCents(), random, shoes, now());
        jdbc.update("INSERT INTO card_games (bet_id, state, next_deadline) VALUES (?, ?::jsonb, ?)",
                betId, write(game), deadline(game));
        save(bet, game);
        return true;
    }

    private void drop(UUID betId, UUID userId) {
        jdbc.update("DELETE FROM card_players WHERE bet_id = ? AND user_id = ?", betId, userId);
    }

    // ----- Decisions -----

    /** {@code hit}, {@code stand}, {@code double} or {@code split} for a player; {@code deal} for the round's dealer. */
    @Transactional
    public Outcome act(Jwt jwt, UUID betId, String action, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID userId = UUID.fromString(jwt.getSubject());
        List<UUID> players = players(betId);
        if (!players.contains(userId)) {
            return jdbc.queryForList("SELECT id FROM bets WHERE id = ?", UUID.class, betId).isEmpty()
                    ? Outcome.NOT_FOUND : Outcome.FORBIDDEN;
        }
        Bet bet = lockGame(betId, players);
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (bet == null) return Outcome.NOT_FOUND;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, ACT, betId + "|" + action);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("PENDING")) throw new Refused("This game is over.");
        JackblackGame game = load(betId);
        if (game == null) throw new Refused("The cards aren't out yet.");
        JackblackRules rules = bet.rules();
        long now = now();
        // A lapsed timer is applied instead of the tap, so a late tap can't land on the next step.
        if (game.timeout(rules, shoes, now)) {
            save(bet, game);
            authentication.requireUnexpired(jwt);
            return Outcome.DONE;
        }
        try {
            if (action.equals("deal")) {
                if (!game.dealerId().equals(userId)) throw new Refused("You aren't dealing this round.");
                game.dealerStep(rules, shoes, now);
            } else {
                game.choose(rules, userId, JackblackGame.Action.valueOf(action.toUpperCase(Locale.ROOT)), shoes,
                        now);
            }
        } catch (JackblackGame.NotAllowed refused) {
            // Only the rules' own refusals reach the player; any other failure stays a server error.
            throw new Refused(refused.getMessage());
        }
        save(bet, game);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    /** Applies a lapsed timer (worker). Returns whether the game moved on. */
    @Transactional
    public boolean expire(UUID betId) {
        Bet bet = lockGame(betId, players(betId));
        if (bet == null || !bet.status().equals("PENDING")) return false;
        JackblackGame game = load(betId);
        if (game == null || !game.timeout(bet.rules(), shoes, now())) return false;
        save(bet, game);
        return true;
    }

    // ----- Reading -----

    /** What a player can decide right now (demo bots read this; it shows only what that player can see). */
    public record Turn(List<String> cards, int total, boolean soft, int dealerUp, boolean canDouble,
            boolean canSplit) {}

    @Transactional(readOnly = true)
    public Optional<Turn> turn(UUID betId, UUID userId) {
        JackblackGame game = load(betId);
        if (game == null || !game.deciding(userId)) return Optional.empty();
        JackblackRules rules = rules(betId);
        JackblackGame.Seat seat = game.seatOf(userId);
        JackblackGame.Hand hand = JackblackGame.current(seat);
        String up = game.dealer.getFirst();
        int dealerUp = JackblackGame.rank(up) == 'A' ? 11 : JackblackGame.value(up);
        return Optional.of(new Turn(List.copyOf(hand.cards), JackblackGame.total(hand.cards),
                JackblackGame.soft(hand.cards), dealerUp, game.canDouble(rules, hand),
                game.canSplit(rules, seat, hand)));
    }

    /** The dealer's button right now (flip, hit or stand) when {@code userId} is dealing, for demo bots. */
    @Transactional(readOnly = true)
    public Optional<String> dealerMove(UUID betId, UUID userId) {
        JackblackGame game = load(betId);
        if (game == null || !JackblackGame.DEALER.equals(game.phase) || !game.dealerId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(game.dealerMove());
    }

    /**
     * What {@code viewer} may see (domain rules, "Jackblack", "Who sees what"): no shoe, the dealer's hole card only
     * once it's turned over, and nobody's choice for this step (only whether they've made it) except the viewer's
     * own. In a live round a player sees only their own seat's cards and the dealer's; the round's dealer sees every
     * seat. Other seats come with just who's there and whether they're still deciding. Each round's shuffle
     * commitment is shown from the deal; its secret (deck count and seed) only once the game is over.
     */
    Map<String, Object> view(UUID betId, UUID viewer) {
        JackblackGame game = load(betId);
        Map<String, Object> view = new LinkedHashMap<>();
        if (game == null) {
            view.put("phase", "waiting");
            view.put("players", players(betId));
            return view;
        }
        JackblackRules rules = rules(betId);
        view.put("phase", game.phase);
        boolean pause = JackblackGame.RESULTS.equals(game.phase);
        // During the pause between rounds the finished round is the one shown (its results are in history).
        view.put("round", pause ? game.round : Math.min(game.round + 1, game.rounds));
        view.put("rounds", game.rounds);
        view.put("order", game.order);
        if (pause) {
            view.put("deadline", Instant.ofEpochMilli(game.deadline).toString());
        } else if (!JackblackGame.DONE.equals(game.phase)) {
            view.put("dealerId", game.dealerId());
            view.put("dealer", game.revealed ? game.dealer : List.of(game.dealer.getFirst(), "??"));
            if (game.revealed) view.put("dealerTotal", JackblackGame.total(game.dealer));
            if (game.deadline > 0) view.put("deadline", Instant.ofEpochMilli(game.deadline).toString());
            String move = game.dealerMove();
            if (move != null) view.put("dealerMove", move);
            boolean dealing = game.dealerId().equals(viewer);
            List<Map<String, Object>> seats = new ArrayList<>();
            for (JackblackGame.Seat seat : game.seats) {
                seats.add(dealing || seat.userId.equals(viewer)
                        ? seat(rules, game, seat, viewer)
                        : hiddenSeat(game, seat));
            }
            view.put("seats", seats);
        }
        boolean over = JackblackGame.DONE.equals(game.phase);
        List<Map<String, Object>> shuffles = new ArrayList<>();
        for (CardShuffle.Secret secret : game.secrets) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("round", secret.round);
            s.put("commitment", secret.commitment());
            if (over) {
                s.put("decks", secret.decks);
                s.put("seed", secret.seed);
            }
            shuffles.add(s);
        }
        view.put("shuffles", shuffles);
        view.put("totals", game.totals);
        List<Map<String, Object>> history = new ArrayList<>();
        for (JackblackGame.Round round : game.history) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("number", round.number);
            r.put("dealerId", round.dealerId);
            r.put("dealer", round.dealer);
            r.put("dealerTotal", JackblackGame.total(round.dealer));
            r.put("dealerDeltaCents", round.dealerDelta);
            List<Map<String, Object>> seats = new ArrayList<>();
            for (JackblackGame.Seat seat : round.seats) seats.add(seat(rules, game, seat, null));
            r.put("seats", seats);
            history.add(r);
        }
        view.put("history", history);
        return view;
    }

    /** Another player's seat in a live round: who's there and whether they're still choosing, never their cards. */
    private static Map<String, Object> hiddenSeat(JackblackGame game, JackblackGame.Seat seat) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", seat.userId);
        if (JackblackGame.PLAY.equals(game.phase) && JackblackGame.current(seat) != null) {
            view.put("deciding", seat.pending == null);
        }
        return view;
    }

    private static Map<String, Object> seat(JackblackRules rules, JackblackGame game, JackblackGame.Seat seat,
            UUID viewer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", seat.userId);
        List<Map<String, Object>> hands = new ArrayList<>();
        JackblackGame.Hand current = JackblackGame.PLAY.equals(game.phase) ? JackblackGame.current(seat) : null;
        for (JackblackGame.Hand hand : seat.hands) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("cards", hand.cards);
            h.put("total", JackblackGame.total(hand.cards));
            h.put("soft", JackblackGame.soft(hand.cards));
            h.put("stakeCents", hand.stake);
            h.put("doubled", hand.doubled);
            h.put("done", hand.done);
            h.put("current", viewer != null && hand == current);
            if (hand.result != null) {
                h.put("result", hand.result);
                h.put("deltaCents", hand.delta);
            }
            hands.add(h);
        }
        view.put("hands", hands);
        if (viewer != null && current != null) {
            view.put("deciding", seat.pending == null);
            if (seat.userId.equals(viewer)) {
                if (seat.pending != null) view.put("choice", seat.pending.toLowerCase(Locale.ROOT));
                view.put("canDouble", game.canDouble(rules, current));
                view.put("canSplit", game.canSplit(rules, seat, current));
            }
        }
        if (viewer == null) view.put("deltaCents", seat.delta);
        return view;
    }

    // ----- Internals -----

    private void save(Bet bet, JackblackGame game) {
        jdbc.update("UPDATE card_games SET state = ?::jsonb, next_deadline = ? WHERE bet_id = ?",
                write(game), deadline(game), bet.id());
        jdbc.update("UPDATE bets SET version = version + 1 WHERE id = ?", bet.id());
        changed(bet.tableId(), bet.id());
        if (JackblackGame.DONE.equals(game.phase)) settle(bet, game);
    }

    /** Pays the finished ticket: each player's total over every round, zero-sum. */
    private void settle(Bet bet, JackblackGame game) {
        if (jdbc.update("""
                UPDATE bets SET status = 'SETTLED', closed_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'PENDING'
                """, bet.id()) != 1) {
            throw new IllegalStateException("Jackblack bet changed while paying out");
        }
        if (!bet.forFun()) {
            for (Map.Entry<UUID, Long> entry : game.totals.entrySet()) {
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

    /** Locks the given players in id order, then the table, the bet and its game. Null if the bet is gone. */
    private Bet lockGame(UUID betId, List<UUID> players) {
        String ids = "{" + String.join(",", players.stream().map(UUID::toString).toList()) + "}";
        jdbc.query("SELECT id FROM users WHERE id = ANY (?::uuid[]) ORDER BY id FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), ids);
        return lockTableAndBet(betId);
    }

    private Bet lockTableAndBet(UUID betId) {
        List<UUID> tables = jdbc.queryForList("SELECT table_id FROM bets WHERE id = ? AND mode = ?", UUID.class,
                betId, MODE);
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

    /** Players in joining order. */
    List<UUID> players(UUID betId) {
        return jdbc.queryForList("""
                SELECT user_id FROM card_players WHERE bet_id = ? ORDER BY joined_at, user_id
                """, UUID.class, betId);
    }

    private JackblackRules rules(UUID betId) {
        return JackblackRules.fromConfig(read(jdbc.queryForObject("SELECT config::text FROM bets WHERE id = ?",
                String.class, betId)));
    }

    private JackblackGame load(UUID betId) {
        List<String> states = jdbc.queryForList("SELECT state::text FROM card_games WHERE bet_id = ?",
                String.class, betId);
        if (states.isEmpty()) return null;
        try {
            return json.readValue(states.getFirst(), JackblackGame.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable Jackblack game", exception);
        }
    }

    private BetResolutionWriter.LockedBet lockedBet(Bet bet) {
        return new BetResolutionWriter.LockedBet(bet.id(), bet.tableId(), 0, bet.forFun(), List.of(), List.of());
    }

    private void changed(UUID tableId, UUID betId) {
        outbox.append(OutboxEvent.BET_CARD_GAME_CHANGED, betId, Map.of("tableId", tableId, "betId", betId));
    }

    private long now() {
        Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (now == null) throw new IllegalStateException("Database clock is unavailable");
        return now.getTime();
    }

    private static Timestamp deadline(JackblackGame game) {
        Long next = game.nextDeadline();
        return next == null ? null : new Timestamp(next);
    }

    private String write(Object value) {
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

    record Bet(UUID id, UUID tableId, UUID proposerId, String status, boolean forFun, long anteCents,
            JsonNode config, boolean open) {
        JackblackRules rules() {
            return JackblackRules.fromConfig(config);
        }
    }
}
