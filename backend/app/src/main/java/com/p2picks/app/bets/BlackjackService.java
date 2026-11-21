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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blackjack hands (domain rules, "Blackjack"): propose (the proposer deals and banks), sit down with a bet while the
 * join window is open, deal when it closes, decisions, timeouts, and paying out through the ledger.
 *
 * <p>Locks: every write that can touch the hand locks the dealer and every seated player in id order, then the
 * table, then the bet and its round, the same order other bet writes use. Seating locks only the player taking or
 * leaving the seat, then the table and the bet.
 */
@Service
public class BlackjackService {
    public enum Outcome { DONE, REPLAYED, CONFLICT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    /** Something the hand's state doesn't allow right now (409); the message is shown to the player. */
    static class Refused extends RuntimeException {
        Refused(String message) {
            super(message);
        }
    }

    static class DebtCapExceeded extends RuntimeException {}

    /** Seats changed between reading and locking them; the caller retries. */
    static class SeatsChanged extends RuntimeException {}

    public static final String MODE = "blackjack";
    static final String NOBODY_SAT = "Nobody sat down";
    static final String DEALER_GONE = "The dealer left the table";
    static final String DEALER_CANNOT_COVER = "The dealer's points limit couldn't cover anyone";
    private static final String PROPOSE = "bet.blackjack.propose.v1";
    private static final String SIT = "bet.blackjack.sit.v1";
    private static final String LEAVE = "bet.blackjack.leave.v1";
    private static final String ACT = "bet.blackjack.act.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MutationAuthentication authentication;
    private final IdempotencyGuard idempotency;
    private final DebtQuery debt;
    private final Outbox outbox;
    private final BetProposalWriter proposals;
    private final BetResolutionWriter resolutions;
    private final SecureRandom random = new SecureRandom();

    BlackjackService(JdbcTemplate jdbc, ObjectMapper json, MutationAuthentication authentication,
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

    // ----- Propose -----

    /** The dealer's worst case with every seat at the maximum bet has to fit their points limit. */
    @Transactional
    public BetProposalWriter.Result propose(Jwt jwt, UUID tableId, BlackjackRules rules, boolean forFun, String key) {
        String config = write(rules.toMap());
        return proposals.create(jwt, tableId, key, PROPOSE, tableId + "|" + forFun + "|" + config,
                BetProposalWriter.Gate.BLACKJACK,
                () -> new BetProposalWriter.NewBet(MODE, null, config, "Blackjack", "[\"Players\",\"Dealer\"]",
                        rules.minBetCents(), forFun, rules.joinSec(), null, null, null,
                        forFun ? 0 : rules.worstCaseFullTable()));
    }

    // ----- Seats -----

    /** Sits down (or changes the bet) while the join window is open. */
    @Transactional
    public Outcome sit(Jwt jwt, UUID betId, long betCents, String key) {
        return seat(jwt, betId, betCents, key);
    }

    @Transactional
    public Outcome leave(Jwt jwt, UUID betId, String key) {
        return seat(jwt, betId, null, key);
    }

    private Outcome seat(Jwt jwt, UUID betId, Long betCents, String key) {
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
        String operation = betCents == null ? LEAVE : SIT;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, operation,
                betId + (betCents == null ? "" : "|" + betCents));
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("ACTIVE") || !bet.open()) throw new Refused("The cards are already out.");
        if (bet.dealerId().equals(userId)) throw new Refused("You're the dealer.");

        int changed;
        if (betCents == null) {
            changed = jdbc.update("DELETE FROM blackjack_seats WHERE bet_id = ? AND user_id = ?", betId, userId);
        } else {
            BlackjackRules rules = bet.rules();
            if (betCents < rules.minBetCents() || betCents > rules.maxBetCents()) {
                throw new Refused("Bet between the table's minimum and maximum.");
            }
            if (!debt.canRiskForLockedUser(userId, betCents, bet.forFun())) throw new DebtCapExceeded();
            boolean seated = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM blackjack_seats WHERE bet_id = ? AND user_id = ?)", Boolean.class,
                    betId, userId));
            if (seated) {
                changed = jdbc.update("""
                        UPDATE blackjack_seats SET bet_cents = ?, stake_cents = ?
                        WHERE bet_id = ? AND user_id = ? AND bet_cents <> ?
                        """, betCents, betCents, betId, userId, betCents);
            } else {
                List<Integer> taken = jdbc.queryForList(
                        "SELECT seat_no FROM blackjack_seats WHERE bet_id = ? ORDER BY seat_no", Integer.class, betId);
                if (taken.size() >= rules.seats()) throw new Refused("Every seat is taken.");
                int seatNo = 1;
                while (taken.contains(seatNo)) seatNo++;
                jdbc.update("""
                        INSERT INTO bet_participants (bet_id, user_id) VALUES (?, ?)
                        ON CONFLICT (bet_id, user_id) DO NOTHING
                        """, betId, userId);
                changed = jdbc.update("""
                        INSERT INTO blackjack_seats (bet_id, user_id, seat_no, bet_cents, stake_cents)
                        VALUES (?, ?, ?, ?, ?)
                        """, betId, userId, seatNo, betCents, betCents);
            }
        }
        if (changed != 0) changed(bet.tableId(), betId);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    // ----- Deal -----

    /** Deals once the join window has closed. Returns false when it wasn't due or is already dealt. */
    @Transactional
    public boolean deal(UUID betId) {
        return deal(betId, rules -> BlackjackGame.shuffledShoe(rules.decks(), random));
    }

    /** Deals from the given shoe (tests stack it). */
    @Transactional
    public boolean deal(UUID betId, java.util.function.Function<BlackjackRules, List<String>> shoe) {
        List<UUID> seated = jdbc.queryForList("SELECT user_id FROM blackjack_seats WHERE bet_id = ?", UUID.class,
                betId);
        Bet bet = lockHand(betId, seated);
        if (bet == null || !bet.status().equals("ACTIVE") || bet.open()) return false;
        if (!new java.util.HashSet<>(seated).equals(new java.util.HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM blackjack_seats WHERE bet_id = ?", UUID.class, betId)))) {
            throw new SeatsChanged();
        }
        BlackjackRules rules = bet.rules();
        // Seats in order, dropping anyone who can no longer bet here or afford their bet.
        List<BlackjackGame.Buyer> buyers = new ArrayList<>();
        for (SeatRow seat : seats(betId)) {
            boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM users u JOIN table_members m ON m.user_id = u.id AND m.table_id = ?
                        WHERE u.id = ? AND u.status = 'ACTIVE' AND u.role = 'PLAYER' AND m.can_bet)
                    """, Boolean.class, bet.tableId(), seat.userId()))
                    && debt.canRiskForLockedUser(seat.userId(), seat.betCents(), bet.forFun());
            if (eligible) buyers.add(new BlackjackGame.Buyer(seat.userId(), seat.seatNo(), seat.betCents()));
            else jdbc.update("DELETE FROM blackjack_seats WHERE bet_id = ? AND user_id = ?", betId, seat.userId());
        }
        String wash = null;
        boolean dealerHere = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM users u JOIN table_members m ON m.user_id = u.id AND m.table_id = ?
                    WHERE u.id = ? AND u.status = 'ACTIVE')
                """, Boolean.class, bet.tableId(), bet.dealerId()));
        if (!dealerHere) wash = DEALER_GONE;
        else if (buyers.isEmpty()) wash = NOBODY_SAT;
        else if (!bet.forFun()) {
            // The dealer covers the latest seats last: drop them until the worst case fits the dealer's limit.
            while (!buyers.isEmpty() && !debt.canRiskForLockedUser(bet.dealerId(), worstCase(rules, buyers), false)) {
                BlackjackGame.Buyer last = buyers.removeLast();
                jdbc.update("DELETE FROM blackjack_seats WHERE bet_id = ? AND user_id = ?", betId, last.userId());
            }
            if (buyers.isEmpty()) wash = DEALER_CANNOT_COVER;
        }
        if (jdbc.update("""
                UPDATE bets SET status = 'PENDING', locked_at = clock_timestamp(), remainder_rule = 'proposer',
                    version = version + 1 WHERE id = ? AND status = 'ACTIVE'
                """, betId) != 1) {
            throw new IllegalStateException("Blackjack bet changed while dealing");
        }
        outbox.append(OutboxEvent.BET_LOCKED, betId, Map.of("tableId", bet.tableId(), "betId", betId));
        if (wash != null) {
            resolutions.apply(lockedBet(bet), null, wash, null, false);
            return true;
        }
        long now = now();
        BlackjackGame game = BlackjackGame.deal(rules, buyers, shoe.apply(rules), now);
        jdbc.update("INSERT INTO blackjack_rounds (bet_id, state, next_deadline) VALUES (?, ?::jsonb, ?)",
                betId, write(game), deadline(game));
        save(bet, game);
        return true;
    }

    // ----- Decisions -----

    @Transactional
    public Outcome act(Jwt jwt, UUID betId, String action, String key) {
        if (!IdempotencyGuard.validKey(key)) throw new IllegalArgumentException("Invalid idempotency key");
        UUID userId = UUID.fromString(jwt.getSubject());
        List<UUID> seated = jdbc.queryForList("SELECT user_id FROM blackjack_seats WHERE bet_id = ?", UUID.class,
                betId);
        boolean dealerMove = action.equals("dealer");
        if (!dealerMove && !seated.contains(userId)) {
            return jdbc.queryForList("SELECT id FROM bets WHERE id = ?", UUID.class, betId).isEmpty()
                    ? Outcome.NOT_FOUND : Outcome.FORBIDDEN;
        }
        Bet bet = lockHand(betId, seated);
        if (!authentication.lockActive(jwt)) return Outcome.UNAUTHORIZED;
        authentication.requireUnexpired(jwt);
        if (bet == null) return Outcome.NOT_FOUND;
        if (dealerMove && !bet.dealerId().equals(userId)) return Outcome.FORBIDDEN;
        IdempotencyGuard.Claim claim = idempotency.claim(userId, key, ACT, betId + "|" + action);
        authentication.requireUnexpired(jwt);
        if (claim == IdempotencyGuard.Claim.CONFLICT) return Outcome.CONFLICT;
        if (claim == IdempotencyGuard.Claim.REPLAY) return Outcome.REPLAYED;
        if (!bet.status().equals("PENDING")) throw new Refused("This hand is over.");
        BlackjackGame game = load(betId);
        if (game == null) throw new Refused("The cards aren't out yet.");
        BlackjackRules rules = bet.rules();
        long now = now();
        // Apply any lapsed deadline first, so a late decision can't skip someone's timeout.
        game.timeout(rules, now);
        try {
            switch (action) {
                case "insurance", "no_insurance" -> {
                    long cost = game.insuranceCost(userId, action.equals("insurance"));
                    if (!debt.canRiskForLockedUser(userId, cost, bet.forFun())) throw new DebtCapExceeded();
                    game.insure(rules, userId, action.equals("insurance"), now);
                }
                case "dealer" -> game.dealerStep(rules, now);
                case "hit", "stand", "double", "split" -> {
                    BlackjackGame.Action move = BlackjackGame.Action.valueOf(action.toUpperCase(java.util.Locale.ROOT));
                    long extra = game.extraStake(rules, userId, move);
                    if (extra > 0 && !debt.canRiskForLockedUser(userId, extra, bet.forFun())) {
                        throw new DebtCapExceeded();
                    }
                    game.act(rules, userId, move, now);
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
        } catch (IllegalStateException refused) {
            throw new Refused(refused.getMessage());
        }
        save(bet, game);
        authentication.requireUnexpired(jwt);
        return Outcome.DONE;
    }

    /** Applies lapsed deadlines (worker). Returns whether the hand moved on. */
    @Transactional
    public boolean expire(UUID betId) {
        List<UUID> seated = jdbc.queryForList("SELECT user_id FROM blackjack_seats WHERE bet_id = ?", UUID.class,
                betId);
        Bet bet = lockHand(betId, seated);
        if (bet == null || !bet.status().equals("PENDING")) return false;
        BlackjackGame game = load(betId);
        if (game == null || !game.timeout(bet.rules(), now())) return false;
        save(bet, game);
        return true;
    }

    // ----- Reading -----

    /** The dealer's move when it's their turn (flip, hit or stand), for demo bots that deal. */
    @Transactional(readOnly = true)
    public java.util.Optional<String> dealerMove(UUID betId) {
        BlackjackGame game = load(betId);
        return java.util.Optional.ofNullable(game == null ? null : game.dealerMove(bet(betId)));
    }

    private BlackjackRules bet(UUID betId) {
        return BlackjackRules.fromConfig(read(jdbc.queryForObject("SELECT config::text FROM bets WHERE id = ?",
                String.class, betId)));
    }

    /**
     * A seated player's decision right now, or empty when it isn't theirs to make (demo bots read this; it shows
     * only what that player can see). {@code dealerUp} counts an ace as 11.
     */
    public record Turn(String phase, List<String> cards, int total, boolean soft, int dealerUp, boolean canDouble,
            boolean canSplit) {}

    @Transactional(readOnly = true)
    public java.util.Optional<Turn> turn(UUID betId, UUID userId) {
        BlackjackGame game = load(betId);
        if (game == null) return java.util.Optional.empty();
        BlackjackGame.Seat seat = game.seats.stream().filter(s -> s.userId.equals(userId)).findFirst().orElse(null);
        if (seat == null) return java.util.Optional.empty();
        String up = game.dealer.getFirst();
        int dealerUp = BlackjackGame.rank(up) == 'A' ? 11 : BlackjackGame.value(up);
        BlackjackGame.Hand first = seat.hands.getFirst();
        if (game.phase.equals(BlackjackGame.PHASE_INSURANCE) && seat.insurance == null) {
            return java.util.Optional.of(new Turn("insurance", first.cards, BlackjackGame.total(first.cards),
                    BlackjackGame.soft(first.cards), dealerUp, false, false));
        }
        if (!game.phase.equals(BlackjackGame.PHASE_PLAY)) return java.util.Optional.empty();
        if (game.turn != null && game.seats.get(game.turn) != seat) return java.util.Optional.empty();
        BlackjackGame.Hand hand = seat.hands.stream().filter(h -> !h.done).findFirst().orElse(null);
        if (hand == null) return java.util.Optional.empty();
        BlackjackRules rules = BlackjackRules.fromConfig(read(jdbc.queryForObject(
                "SELECT config::text FROM bets WHERE id = ?", String.class, betId)));
        return java.util.Optional.of(new Turn("play", List.copyOf(hand.cards), BlackjackGame.total(hand.cards),
                BlackjackGame.soft(hand.cards), dealerUp, game.canDouble(rules, hand),
                game.canSplit(rules, seat, hand)));
    }

    /** What a viewer may see of a hand: no shoe, and the dealer's hole card only once it's turned over. */
    Map<String, Object> view(UUID betId) {
        BlackjackGame game = load(betId);
        List<Map<String, Object>> seats = new ArrayList<>();
        if (game == null) {
            for (SeatRow seat : seats(betId)) {
                seats.add(Map.of("userId", seat.userId(), "seat", seat.seatNo(), "betCents", seat.betCents()));
            }
            return Map.of("phase", "waiting", "seats", seats, "dealer", List.of());
        }
        for (BlackjackGame.Seat seat : game.seats) {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("userId", seat.userId);
            view.put("seat", seat.seat);
            view.put("betCents", seat.bet);
            if (seat.insurance != null) view.put("insuranceCents", seat.insurance);
            view.put("insuranceOpen", game.phase.equals(BlackjackGame.PHASE_INSURANCE) && seat.insurance == null);
            if (seat.deadline > 0) view.put("deadline", Instant.ofEpochMilli(seat.deadline).toString());
            List<Map<String, Object>> hands = new ArrayList<>();
            for (BlackjackGame.Hand hand : seat.hands) {
                Map<String, Object> h = new java.util.LinkedHashMap<>();
                h.put("cards", hand.cards);
                h.put("total", BlackjackGame.total(hand.cards));
                h.put("soft", BlackjackGame.soft(hand.cards));
                h.put("stakeCents", hand.stake);
                h.put("doubled", hand.doubled);
                h.put("done", hand.done);
                if (hand.result != null) {
                    h.put("result", hand.result);
                    h.put("deltaCents", hand.delta);
                }
                hands.add(h);
            }
            view.put("hands", hands);
            if (game.phase.equals(BlackjackGame.PHASE_DONE)) view.put("deltaCents", seat.delta);
            seats.add(view);
        }
        List<String> dealer = game.revealed ? game.dealer : List.of(game.dealer.getFirst(), "??");
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("phase", game.phase);
        view.put("dealer", dealer);
        if (game.revealed) view.put("dealerTotal", BlackjackGame.total(game.dealer));
        if (game.turn != null) view.put("turnSeat", game.seats.get(game.turn).seat);
        String dealerMove = game.dealerMove(bet(betId));
        if (dealerMove != null) {
            view.put("dealerMove", dealerMove);
            view.put("dealerDeadline", Instant.ofEpochMilli(game.dealerDeadline).toString());
        }
        view.put("seats", seats);
        if (game.phase.equals(BlackjackGame.PHASE_DONE)) view.put("dealerDeltaCents", game.dealerDelta);
        return view;
    }

    // ----- Internals -----

    private void save(Bet bet, BlackjackGame game) {
        jdbc.update("UPDATE blackjack_rounds SET state = ?::jsonb, next_deadline = ? WHERE bet_id = ?",
                write(game), deadline(game), bet.id());
        for (BlackjackGame.Seat seat : game.seats) {
            jdbc.update("UPDATE blackjack_seats SET stake_cents = ? WHERE bet_id = ? AND user_id = ?",
                    BlackjackGame.staked(seat), bet.id(), seat.userId);
        }
        jdbc.update("UPDATE bets SET version = version + 1 WHERE id = ?", bet.id());
        changed(bet.tableId(), bet.id());
        if (game.phase.equals(BlackjackGame.PHASE_DONE)) settle(bet, game);
    }

    /** Pays the finished hand: each seat's result against the dealer, zero-sum. */
    private void settle(Bet bet, BlackjackGame game) {
        Map<UUID, Long> deltas = new java.util.LinkedHashMap<>();
        if (!bet.forFun()) {
            for (BlackjackGame.Seat seat : game.seats) deltas.merge(seat.userId, seat.delta, Math::addExact);
            deltas.merge(bet.dealerId(), game.dealerDelta, Math::addExact);
        }
        if (jdbc.update("""
                UPDATE bets SET status = 'SETTLED', closed_at = clock_timestamp(), version = version + 1
                WHERE id = ? AND status = 'PENDING'
                """, bet.id()) != 1) {
            throw new IllegalStateException("Blackjack bet changed while paying out");
        }
        for (Map.Entry<UUID, Long> entry : deltas.entrySet()) {
            if (entry.getValue() == 0) continue;
            jdbc.update("""
                    INSERT INTO ledger_entries (id, table_id, bet_id, user_id, delta_cents) VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), bet.tableId(), bet.id(), entry.getKey(), entry.getValue());
        }
        jdbc.update("UPDATE blackjack_rounds SET next_deadline = NULL WHERE bet_id = ?", bet.id());
        outbox.append(OutboxEvent.BET_RESOLVED, bet.id(), Map.of("tableId", bet.tableId(), "betId", bet.id()));
    }

    private static long worstCase(BlackjackRules rules, List<BlackjackGame.Buyer> buyers) {
        long total = 0;
        for (BlackjackGame.Buyer buyer : buyers) {
            total = Math.addExact(total, BlackjackRules.worstCase(buyer.bet(), rules.maxSplits()));
        }
        return total;
    }

    /** Locks the dealer and the given players in id order, then the table and the bet. Null if the bet is gone. */
    private Bet lockHand(UUID betId, List<UUID> seated) {
        List<UUID> dealer = jdbc.queryForList("SELECT proposer_id FROM bets WHERE id = ? AND mode = ?", UUID.class,
                betId, MODE);
        if (dealer.isEmpty()) return null;
        List<UUID> users = new ArrayList<>(seated);
        if (!users.contains(dealer.getFirst())) users.add(dealer.getFirst());
        String ids = "{" + String.join(",", users.stream().map(UUID::toString).toList()) + "}";
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
                SELECT id, table_id, proposer_id, status, is_4fun, config, locks_at > clock_timestamp() AS open
                FROM bets WHERE id = ? FOR UPDATE
                """, (rs, row) -> new Bet(rs.getObject("id", UUID.class), rs.getObject("table_id", UUID.class),
                        rs.getObject("proposer_id", UUID.class), rs.getString("status"), rs.getBoolean("is_4fun"),
                        read(rs.getString("config")), rs.getBoolean("open")), betId);
        if (bets.isEmpty()) return null;
        jdbc.queryForList("SELECT bet_id FROM blackjack_rounds WHERE bet_id = ? FOR UPDATE", UUID.class, betId);
        return bets.getFirst();
    }

    private List<SeatRow> seats(UUID betId) {
        return jdbc.query("""
                SELECT user_id, seat_no, bet_cents FROM blackjack_seats WHERE bet_id = ? ORDER BY seat_no
                """, (rs, row) -> new SeatRow(rs.getObject("user_id", UUID.class), rs.getInt("seat_no"),
                        rs.getLong("bet_cents")), betId);
    }

    private BlackjackGame load(UUID betId) {
        List<String> states = jdbc.queryForList("SELECT state::text FROM blackjack_rounds WHERE bet_id = ?",
                String.class, betId);
        if (states.isEmpty()) return null;
        try {
            return json.readValue(states.getFirst(), BlackjackGame.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable Blackjack hand", exception);
        }
    }

    private BetResolutionWriter.LockedBet lockedBet(Bet bet) {
        return new BetResolutionWriter.LockedBet(bet.id(), bet.tableId(), 0, bet.forFun(), List.of(), null, List.of());
    }

    private void changed(UUID tableId, UUID betId) {
        outbox.append(OutboxEvent.BET_BLACKJACK_CHANGED, betId, Map.of("tableId", tableId, "betId", betId));
    }

    private long now() {
        Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (now == null) throw new IllegalStateException("Database clock is unavailable");
        return now.getTime();
    }

    private static Timestamp deadline(BlackjackGame game) {
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

    record Bet(UUID id, UUID tableId, UUID dealerId, String status, boolean forFun, JsonNode config, boolean open) {
        BlackjackRules rules() {
            return BlackjackRules.fromConfig(config);
        }
    }

    private record SeatRow(UUID userId, int seatNo, long betCents) {}
}
