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
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold'em tickets (domain rules, "Hold'em"): propose (the proposer is the first player), join or leave while the join
 * window is open, deal when it closes, then the hands: bets in turn, timeouts, rebuys, showing at a showdown, and
 * paying out everyone's result through the ledger at the end.
 *
 * <p>Each player's worst case, both buy-ins, is held against their points limit: checked when they join and again at
 * the deal, where anyone who can no longer cover it is dropped.
 *
 * <p>Locks follow Jackblack's order: players in id order, then the table, then the bet and its game.
 */
@Service
public class HoldemService {
    public enum Outcome { DONE, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    /** Something the game's state doesn't allow right now (409); the message is shown to the player. */
    static class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    static class DebtCapExceeded extends RuntimeException {}

    static class PlayersChanged extends RuntimeException {}

    public static final String MODE = "holdem";
    static final String NOT_ENOUGH = "Not enough players";
    static final Set<String> ACTIONS = Set.of("fold", "check", "call", "raise", "all_in", "rebuy", "pass",
            "check_fold", "call_any", "no_pre_move");
    private static final String PROPOSE = "bet.holdem.propose.v1";
    private static final String JOIN = "bet.holdem.join.v1";
    private static final String LEAVE = "bet.holdem.leave.v1";
    private static final String ACT = "bet.holdem.act.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;
    private final BetProposalWriter proposals;
    private final BetResolutionWriter resolutions;
    private final SecureRandom random = new SecureRandom();
    /** Where each hand's deck comes from: its secret's shuffle (tests stack it). */
    private volatile HoldemGame.Decks decks = HoldemGame.FROM_SECRETS;

    HoldemService(JdbcTemplate jdbc, ObjectMapper json, MutationAuthentication authentication,
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

    /** Tests only: deal every hand from {@code decks}; null goes back to the secrets' shuffles. */
    public void dealFrom(HoldemGame.Decks decks) {
        this.decks = decks == null ? HoldemGame.FROM_SECRETS : decks;
    }

    // ----- Propose -----

    /** The wager is the big blind; a 4fun game sends 0 and plays with chips worth nothing. */
    @Transactional
    public BetProposalWriter.Result propose(Jwt jwt, UUID tableId, HoldemRules rules, long wagerCents,
            int durationSec, boolean forFun, String key) {
        String config = write(rules.toMap());
        long risk = forFun ? 0 : HoldemRules.worstCase(wagerCents);
        BetProposalWriter.Result result = proposals.create(jwt, tableId, key, PROPOSE,
                tableId + "|" + wagerCents + "|" + durationSec + "|" + forFun + "|" + config,
                BetProposalWriter.Gate.HOLDEM,
                () -> new BetProposalWriter.NewBet(MODE, null, config, "Hold'em", "[\"Up\",\"Down\"]",
                        wagerCents, forFun, durationSec, null, null, null, risk));
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
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, joining ? JOIN : LEAVE, betId.toString());
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("ACTIVE") || !bet.open()) throw new Refused("The cards are already out.");

        List<UUID> players = players(betId);
        boolean in = players.contains(userId);
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
        } else if (joining && !in) {
            if (players.size() >= bet.rules().maxPlayers()) throw new Refused("The game is full.");
            long risk = bet.forFun() ? 0 : HoldemRules.worstCase(bet.wagerCents());
            if (!debt.canRiskForLockedUser(userId, risk, bet.forFun())) throw new DebtCapExceeded();
            jdbc.update("""
                    INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)
                    ON CONFLICT (bet_id, user_id) DO NOTHING
                    """, betId, userId);
            changed = jdbc.update("INSERT INTO card_players (bet_id, user_id, risk_cents) VALUES (?, ?, ?)",
                    betId, userId, risk);
        }
        if (changed != 0) changed(bet.tableId(), betId);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    // ----- Deal -----

    /** Deals once the join window has closed. Returns false when it wasn't due or is already dealt. */
    @Transactional
    public boolean deal(UUID betId) {
        List<UUID> joined = players(betId);
        Bet bet = lockGame(betId, joined);
        if (bet == null || !bet.status().equals("ACTIVE") || bet.open()) return false;
        if (!new HashSet<>(joined).equals(new HashSet<>(players(betId)))) throw new PlayersChanged();
        long risk = bet.forFun() ? 0 : HoldemRules.worstCase(bet.wagerCents());
        List<UUID> players = new ArrayList<>();
        for (UUID userId : players(betId)) {
            boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users u JOIN table_members m ON m.user_id = u.id AND m.table_id = ?
                        WHERE u.id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER' AND m.can_bet)
                    """, Boolean.class, bet.tableId(), userId));
            // Everyone has to cover both buy-ins; whoever can't any more is dropped.
            if (eligible && (bet.forFun() || debt.canRiskForLockedUser(userId, risk, false))) {
                players.add(userId);
            } else {
                jdbc.update("DELETE FROM card_players WHERE bet_id = ? AND user_id = ?", betId, userId);
            }
        }
        if (jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'ACTIVE'
                """, betId) != 1) {
            throw new IllegalStateException("Hold'em bet changed while dealing");
        }
        outbox.append(OutboxEvent.BET_LOCKED, betId, Map.of("tableId", bet.tableId(), "betId", betId));
        if (players.size() < bet.rules().minPlayers()) {
            resolutions.apply(lockedBet(bet), null, NOT_ENOUGH, null, false);
            return true;
        }
        HoldemGame game = HoldemGame.start(bet.rules(), players,
                HoldemRules.bigBlind(bet.wagerCents(), bet.forFun()), random, decks, now());
        jdbc.update("INSERT INTO card_games (bet_id, state, next_deadline) VALUES (?, ?::jsonb, ?)",
                betId, write(game), deadline(game));
        save(bet, game);
        return true;
    }

    // ----- Playing -----

    /**
     * A player's move: {@code fold}, {@code check}, {@code call}, {@code raise} (with {@code amount}, the raise-to
     * total for this street) or {@code all_in} on their turn; between hands {@code rebuy} or {@code pass} on a
     * rebuy; and while it isn't your turn a pre-move, {@code check_fold} or {@code call_any}, or {@code no_pre_move}
     * to clear it.
     */
    @Transactional
    public Outcome act(Jwt jwt, UUID betId, String action, long amount, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Invalid action");
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
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, ACT, betId + "|" + action + "|" + amount);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("PENDING")) throw new Refused("This game is over.");
        HoldemGame game = load(betId);
        if (game == null) throw new Refused("The cards aren't out yet.");
        HoldemRules rules = bet.rules();
        long now = now();
        // A lapsed timer is applied instead of the tap, so a late tap can't land on the next turn.
        if (game.timeout(rules, decks, now)) {
            save(bet, game);
            authentication.requireUnexpired(jwt);
            return Outcome.DONE;
        }
        try {
            switch (action) {
                case "rebuy" -> game.rebuy(userId, true, now);
                case "pass" -> game.rebuy(userId, false, now);
                case "check_fold" -> game.preMove(userId, HoldemGame.PreMove.CHECK_FOLD);
                case "call_any" -> game.preMove(userId, HoldemGame.PreMove.CALL_ANY);
                case "no_pre_move" -> game.preMove(userId, null);
                default -> game.act(rules, userId, HoldemGame.Action.valueOf(action.toUpperCase(Locale.ROOT)),
                        amount, decks, now);
            }
        } catch (HoldemGame.NotAllowed refused) {
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
        HoldemGame game = load(betId);
        if (game == null || !game.timeout(bet.rules(), decks, now())) return false;
        save(bet, game);
        return true;
    }

    // ----- Reading -----

    /**
     * What a player can do on their turn (demo bots read this; it shows only what that player can see).
     * {@code madeHand} is the best hand they've made with the board, 0 (high card) to 8 (straight flush), or −1
     * before the flop.
     */
    public record Turn(List<String> hole, List<String> board, long toCall, long pot, long minRaiseTo,
            long maxRaiseTo, boolean canRaise, long bigBlind, int madeHand) {}

    @Transactional(readOnly = true)
    public java.util.Optional<Turn> turn(UUID betId, UUID userId) {
        HoldemGame game = load(betId);
        if (game == null || !userId.equals(game.toActId())) return java.util.Optional.empty();
        HoldemGame.Seat seat = game.seatOf(userId);
        int made = -1;
        if (!game.board.isEmpty()) {
            List<String> cards = new ArrayList<>(seat.hole);
            cards.addAll(game.board);
            made = HandEvaluator.best(cards).category().ordinal();
        }
        return java.util.Optional.of(new Turn(List.copyOf(seat.hole), List.copyOf(game.board), game.toCall(seat),
                game.pot(), game.minRaiseTo(seat), game.maxRaiseTo(seat), game.canRaise(seat), game.bigBlind, made));
    }

    /** Between hands, whether {@code userId} has a rebuy to decide (for demo bots). */
    @Transactional(readOnly = true)
    public boolean rebuyOffered(UUID betId, UUID userId) {
        HoldemGame game = load(betId);
        return game != null && HoldemGame.RESULTS.equals(game.phase) && game.rebuyOffered.contains(userId);
    }

    /**
     * What {@code viewer} may see (domain rules, "Hold'em", "Who sees what"): never the deck, and only your own hole
     * cards while a hand is on. At a showdown everyone still in shows. Stacks, bets, the board and whose turn it is are public, as at a real table. Each hand's shuffle
     * commitment is shown from the deal; once the game is over every secret and every hand is revealed (the audit).
     */
    Map<String, Object> view(UUID betId, UUID viewer) {
        HoldemGame game = load(betId);
        Map<String, Object> view = new LinkedHashMap<>();
        if (game == null) {
            view.put("phase", "waiting");
            view.put("players", players(betId));
            return view;
        }
        boolean over = HoldemGame.DONE.equals(game.phase);
        view.put("phase", game.phase);
        view.put("hand", game.hand);
        view.put("hands", game.hands);
        view.put("order", game.order);
        view.put("bigBlindCents", game.bigBlind);
        view.put("smallBlindCents", game.smallBlind);
        view.put("buyInCents", game.buyIn);
        view.put("stacks", game.stacks);
        view.put("buyIns", game.buyIns);
        view.put("out", game.out);
        if (game.deadline > 0) view.put("deadline", Instant.ofEpochMilli(game.deadline).toString());
        if (HoldemGame.PLAY.equals(game.phase)) {
            view.put("buttonId", game.buttonId);
            view.put("smallBlindId", game.smallBlindId);
            view.put("bigBlindId", game.bigBlindId);
            view.put("street", game.street);
            view.put("board", game.board);
            view.put("pot", game.pot());
            view.put("currentBet", game.currentBet);
            UUID toAct = game.toActId();
            if (toAct != null) view.put("toActId", toAct);
            List<Map<String, Object>> seats = new ArrayList<>();
            for (HoldemGame.Seat seat : game.seats) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("userId", seat.userId);
                s.put("committed", seat.committed);
                s.put("contributed", seat.contributed);
                s.put("folded", seat.folded);
                s.put("allIn", seat.allIn);
                seats.add(s);
            }
            view.put("seats", seats);
            HoldemGame.Seat mine = game.seatOf(viewer);
            if (mine != null) {
                Map<String, Object> me = new LinkedHashMap<>();
                me.put("hole", mine.hole);
                // Your best hand so far, once the flop is out (only ever your own cards and the public board).
                if (game.board.size() >= 3) {
                    List<String> cards = new ArrayList<>(mine.hole);
                    cards.addAll(game.board);
                    me.put("handName", HandEvaluator.best(cards).name());
                }
                if (mine.preMove != null) me.put("preMove", mine.preMove.name().toLowerCase(Locale.ROOT));
                if (viewer.equals(toAct)) {
                    me.put("toCall", game.toCall(mine));
                    me.put("canRaise", game.canRaise(mine));
                    me.put("minRaiseTo", game.minRaiseTo(mine));
                    me.put("maxRaiseTo", game.maxRaiseTo(mine));
                }
                view.put("me", me);
            }
        } else if (HoldemGame.RESULTS.equals(game.phase)) {
            view.put("rebuyOffered", game.rebuyOffered);
            HoldemGame.Hand last = game.history.getLast();
            List<String> hole = last.holes.get(viewer);
            if (hole != null) view.put("me", Map.of("hole", hole));
        }
        if (over) view.put("totals", game.totals);
        List<Map<String, Object>> history = new ArrayList<>();
        for (HoldemGame.Hand hand : game.history) history.add(hand(hand, over));
        view.put("history", history);
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
        return view;
    }

    private static Map<String, Object> hand(HoldemGame.Hand hand, boolean over) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("number", hand.number);
        h.put("buttonId", hand.buttonId);
        h.put("smallBlindId", hand.smallBlindId);
        h.put("bigBlindId", hand.bigBlindId);
        h.put("board", hand.board);
        h.put("showdown", hand.showdown);
        List<Map<String, Object>> pots = new ArrayList<>();
        for (HoldemGame.Pot pot : hand.pots) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("amount", pot.amount);
            p.put("winners", pot.winners);
            if (pot.handName != null) p.put("handName", pot.handName);
            pots.add(p);
        }
        h.put("pots", pots);
        h.put("shown", hand.shown);
        h.put("handNames", hand.handNames);
        h.put("atShowdown", hand.atShowdown);
        h.put("deltas", hand.deltas);
        // Everyone's cards only once the game is over: the audit reveals every deck then anyway.
        if (over) h.put("holes", hand.holes);
        return h;
    }

    // ----- Internals -----

    private void save(Bet bet, HoldemGame game) {
        jdbc.update("UPDATE card_games SET state = ?::jsonb, next_deadline = ? WHERE bet_id = ?",
                write(game), deadline(game), bet.id());
        jdbc.update("UPDATE bets SET version = version + 1 WHERE id = ?", bet.id());
        changed(bet.tableId(), bet.id());
        if (HoldemGame.DONE.equals(game.phase)) settle(bet, game);
    }

    /** Pays the finished game: each player's final stack minus their buy-ins, zero-sum. */
    private void settle(Bet bet, HoldemGame game) {
        if (jdbc.update("""
                UPDATE bets SET status = 'SETTLED', closed_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'PENDING'
                """, bet.id()) != 1) {
            throw new IllegalStateException("Hold'em bet changed while paying out");
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

    private HoldemGame load(UUID betId) {
        List<String> states = jdbc.queryForList("SELECT state::text FROM card_games WHERE bet_id = ?",
                String.class, betId);
        if (states.isEmpty()) return null;
        try {
            return json.readValue(states.getFirst(), HoldemGame.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable Hold'em game", exception);
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

    private static Timestamp deadline(HoldemGame game) {
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

    record Bet(UUID id, UUID tableId, UUID proposerId, String status, boolean forFun, long wagerCents,
            JsonNode config, boolean open) {
        HoldemRules rules() {
            return HoldemRules.fromConfig(config);
        }
    }
}
