package com.p2picks.app.bets;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jackblack tickets (domain rules, "Jackblack"): propose (the proposer is the first player), join or leave while the
 * join window is open, deal when it closes, players' turns in order, the dealer's button and timeouts. Seats, storage,
 * locking and the payout are {@link CardGames}'.
 *
 * <p>Every player's worst case over the whole ticket is held against their points limit: checked when they join,
 * and again at the deal, where anyone who can no longer cover it is dropped, latest joiner first.
 */
@Service
public class JackblackService {
    public static final String MODE = "jackblack";
    private static final String PROPOSE = "bet.jackblack.propose.v1";
    /** Everyone's worst case with this many players. */
    private static final CardGames.Risk RISK =
            (bet, players) -> rules(bet).worstCase(bet.wagerCents(), players);

    private final CardGames games;
    private final BetProposalWriter proposals;
    private final SecureRandom random = new SecureRandom();
    /** Where each round's shoe comes from: its secret's shuffle (tests stack it). */
    private volatile JackblackGame.Shoes shoes = JackblackGame.FROM_SECRETS;

    JackblackService(CardGames games, BetProposalWriter proposals) {
        this.games = games;
        this.proposals = proposals;
    }

    /** Tests only: deal every round from {@code shoes}; null goes back to the secrets' shuffles. */
    public void dealFrom(JackblackGame.Shoes shoes) {
        this.shoes = shoes == null ? JackblackGame.FROM_SECRETS : shoes;
    }

    private static JackblackRules rules(CardGames.Bet bet) {
        return JackblackRules.fromConfig(bet.config());
    }

    // ----- Propose and join -----

    /** The proposer joins their own ticket, so their worst case for the fewest players has to fit. */
    @Transactional
    public BetProposalWriter.Result propose(Jwt jwt, UUID tableId, JackblackRules rules, long anteCents,
            int durationSec, boolean forFun, String key) {
        String config = games.write(rules.toMap());
        long risk = forFun ? 0 : rules.worstCase(anteCents, rules.minPlayers());
        BetProposalWriter.Result result = proposals.create(jwt, tableId, key, PROPOSE,
                tableId + "|" + anteCents + "|" + durationSec + "|" + forFun + "|" + config,
                BetProposalWriter.Gate.JACKBLACK,
                () -> new BetProposalWriter.NewBet(MODE, null, config, "Jackblack", "[\"Up\",\"Down\"]",
                        anteCents, forFun, durationSec, null, null, null, risk));
        if (result.outcome() == BetProposalWriter.Outcome.CREATED) {
            games.seatProposer(result.betId(), UUID.fromString(jwt.getSubject()), risk);
        }
        return result;
    }

    @Transactional
    public CardGames.Outcome join(Jwt jwt, UUID betId, String key) {
        return games.seat(MODE, jwt, betId, true, key, RISK);
    }

    @Transactional
    public CardGames.Outcome leave(Jwt jwt, UUID betId, String key) {
        return games.seat(MODE, jwt, betId, false, key, RISK);
    }

    // ----- Deal and play -----

    /** Deals once the join window has closed. Returns false when it wasn't due or is already dealt. */
    @Transactional
    public boolean deal(UUID betId) {
        CardGames.Bet bet = games.lockForDeal(MODE, betId);
        if (bet == null) return false;
        JackblackRules rules = rules(bet);
        List<UUID> players = games.dealIn(bet, RISK, rules.minPlayers());
        games.start(bet, players.size(), rules.minPlayers(),
                now -> JackblackGame.start(rules, players, bet.wagerCents(), random, shoes, now));
        return true;
    }

    /** {@code hit}, {@code stand}, {@code double} or {@code split} for a player; {@code deal} for the round's dealer. */
    @Transactional
    public CardGames.Outcome act(Jwt jwt, UUID betId, String action, String key) {
        return games.act(MODE, JackblackGame.class, jwt, betId, key, action, this::timeout,
                (bet, game, userId, now) -> {
                    if (action.equals("deal")) {
                        if (!game.dealerId().equals(userId)) throw new NotAllowed("You aren't dealing this round.");
                        game.dealerStep(rules(bet), shoes, now);
                    } else {
                        game.choose(rules(bet), userId,
                                JackblackGame.Action.valueOf(action.toUpperCase(Locale.ROOT)), shoes, now);
                    }
                });
    }

    /** Applies a lapsed timer (the worker). Returns whether the game moved on. */
    @Transactional
    public boolean expire(UUID betId) {
        return games.expire(MODE, JackblackGame.class, betId, this::timeout);
    }

    private boolean timeout(CardGames.Bet bet, JackblackGame game, long now) {
        return game.timeout(rules(bet), shoes, now);
    }

    // ----- Reading -----

    /** What a player can decide right now (demo bots read this; it shows only what that player can see). */
    public record Turn(List<String> cards, int total, boolean soft, int dealerUp, boolean canDouble,
            boolean canSplit) {}

    @Transactional(readOnly = true)
    public Optional<Turn> turn(UUID betId, UUID userId) {
        JackblackGame game = games.load(betId, JackblackGame.class);
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
        JackblackGame game = games.load(betId, JackblackGame.class);
        if (game == null || !JackblackGame.DEALER.equals(game.phase) || !game.dealerId().equals(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(game.dealerMove());
    }

    /** Players in joining order. */
    List<UUID> players(UUID betId) {
        return games.players(betId);
    }

    /**
     * What {@code viewer} may see (domain rules, "Jackblack", "Who sees what"): no shoe and the dealer's hole card only
     * once it's turned over. Every seat's cards are public, as at a real table (owner decision, 2026-11-25: each round's
     * shoe is fresh and of a random size, so seeing them doesn't help counting), and so is whose turn it is
     * ({@code turnId}, with that hand marked current). Each round's shuffle
     * commitment is shown from the deal; its secret (deck count and seed) only once the game is over.
     */
    Map<String, Object> view(UUID betId, UUID viewer) {
        JackblackGame game = games.load(betId, JackblackGame.class);
        Map<String, Object> view = new LinkedHashMap<>();
        if (game == null) {
            view.put("phase", "waiting");
            view.put("players", games.players(betId));
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
            JackblackGame.Seat acting = game.acting();
            if (acting != null) view.put("turnId", acting.userId);
            List<Map<String, Object>> seats = new ArrayList<>();
            for (JackblackGame.Seat seat : game.seats) seats.add(seat(rules, game, seat, viewer, acting));
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
            for (JackblackGame.Seat seat : round.seats) seats.add(seat(rules, game, seat, null, null));
            r.put("seats", seats);
            history.add(r);
        }
        view.put("history", history);
        return view;
    }

    /** A seat's hands; the acting seat's hand in play is marked current, and the viewer's own turn adds what's allowed. */
    private static Map<String, Object> seat(JackblackRules rules, JackblackGame game, JackblackGame.Seat seat,
            UUID viewer, JackblackGame.Seat acting) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", seat.userId);
        List<Map<String, Object>> hands = new ArrayList<>();
        JackblackGame.Hand current = seat == acting ? JackblackGame.current(seat) : null;
        for (JackblackGame.Hand hand : seat.hands) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("cards", hand.cards);
            h.put("total", JackblackGame.total(hand.cards));
            h.put("soft", JackblackGame.soft(hand.cards));
            h.put("stakeCents", hand.stake);
            h.put("doubled", hand.doubled);
            h.put("done", hand.done);
            h.put("current", hand == current);
            if (hand.result != null) {
                h.put("result", hand.result);
                h.put("deltaCents", hand.delta);
            }
            hands.add(h);
        }
        view.put("hands", hands);
        if (current != null && seat.userId.equals(viewer)) {
            view.put("canDouble", game.canDouble(rules, current));
            view.put("canSplit", game.canSplit(rules, seat, current));
        }
        if (viewer == null) view.put("deltaCents", seat.delta);
        return view;
    }

    private JackblackRules rules(UUID betId) {
        return JackblackRules.fromConfig(games.config(betId));
    }
}
