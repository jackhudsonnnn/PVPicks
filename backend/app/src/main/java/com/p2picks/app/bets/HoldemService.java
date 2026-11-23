package com.p2picks.app.bets;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hold'em tickets (domain rules, "Hold'em"): propose (the proposer is the first player), join or leave while the join
 * window is open, deal when it closes, then the hands: bets in turn, pre-moves, timeouts and rebuys. Seats, storage,
 * locking and the payout are {@link CardGames}'.
 *
 * <p>Each player's worst case, both buy-ins, is held against their points limit: checked when they join and again at
 * the deal, where anyone who can no longer cover it is dropped.
 */
@Service
public class HoldemService {
    public static final String MODE = "holdem";
    static final Set<String> ACTIONS = Set.of("fold", "check", "call", "raise", "all_in", "rebuy", "pass",
            "check_fold", "call_any", "no_pre_move");
    private static final String PROPOSE = "bet.holdem.propose.v1";
    /** Both buy-ins, whatever the number of players. */
    private static final CardGames.Risk RISK = (bet, players) -> HoldemRules.worstCase(bet.wagerCents());

    private final CardGames games;
    private final BetProposalWriter proposals;
    private final SecureRandom random = new SecureRandom();
    /** Where each hand's deck comes from: its secret's shuffle (tests stack it). */
    private volatile HoldemGame.Decks decks = HoldemGame.FROM_SECRETS;

    HoldemService(CardGames games, BetProposalWriter proposals) {
        this.games = games;
        this.proposals = proposals;
    }

    /** Tests only: deal every hand from {@code decks}; null goes back to the secrets' shuffles. */
    public void dealFrom(HoldemGame.Decks decks) {
        this.decks = decks == null ? HoldemGame.FROM_SECRETS : decks;
    }

    private static HoldemRules rules(CardGames.Bet bet) {
        return HoldemRules.fromConfig(bet.config());
    }

    // ----- Propose and join -----

    /** The wager is the big blind; a 4fun game sends 0 and plays with chips worth nothing. */
    @Transactional
    public BetProposalWriter.Result propose(Jwt jwt, UUID tableId, HoldemRules rules, long wagerCents,
            int durationSec, boolean forFun, String key) {
        String config = games.write(rules.toMap());
        long risk = forFun ? 0 : HoldemRules.worstCase(wagerCents);
        BetProposalWriter.Result result = proposals.create(jwt, tableId, key, PROPOSE,
                tableId + "|" + wagerCents + "|" + durationSec + "|" + forFun + "|" + config,
                BetProposalWriter.Gate.HOLDEM,
                () -> new BetProposalWriter.NewBet(MODE, null, config, "Hold'em", "[\"Up\",\"Down\"]",
                        wagerCents, forFun, durationSec, null, null, null, risk));
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
        HoldemRules rules = rules(bet);
        List<UUID> players = games.dealIn(bet, RISK, rules.minPlayers());
        games.start(bet, players.size(), rules.minPlayers(), now -> HoldemGame.start(rules, players,
                HoldemRules.bigBlind(bet.wagerCents(), bet.forFun()), random, decks, now));
        return true;
    }

    /**
     * A player's move: {@code fold}, {@code check}, {@code call}, {@code raise} (with {@code amount}, the raise-to
     * total for this street) or {@code all_in} on their turn; between hands {@code rebuy} or {@code pass} on a
     * rebuy; and while it isn't your turn a pre-move, {@code check_fold} or {@code call_any}, or {@code no_pre_move}
     * to clear it.
     */
    @Transactional
    public CardGames.Outcome act(Jwt jwt, UUID betId, String action, long amount, String key) {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Invalid action");
        return games.act(MODE, HoldemGame.class, jwt, betId, key, action + "|" + amount, this::timeout,
                (bet, game, userId, now) -> {
                    switch (action) {
                        case "rebuy" -> game.rebuy(userId, true, now);
                        case "pass" -> game.rebuy(userId, false, now);
                        case "check_fold" -> game.preMove(userId, HoldemGame.PreMove.CHECK_FOLD);
                        case "call_any" -> game.preMove(userId, HoldemGame.PreMove.CALL_ANY);
                        case "no_pre_move" -> game.preMove(userId, null);
                        default -> game.act(rules(bet), userId,
                                HoldemGame.Action.valueOf(action.toUpperCase(Locale.ROOT)), amount, decks, now);
                    }
                });
    }

    /** Applies a lapsed timer (the worker). Returns whether the game moved on. */
    @Transactional
    public boolean expire(UUID betId) {
        return games.expire(MODE, HoldemGame.class, betId, this::timeout);
    }

    private boolean timeout(CardGames.Bet bet, HoldemGame game, long now) {
        return game.timeout(rules(bet), decks, now);
    }

    /** Players in joining order. */
    List<UUID> players(UUID betId) {
        return games.players(betId);
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
    public Optional<Turn> turn(UUID betId, UUID userId) {
        HoldemGame game = games.load(betId, HoldemGame.class);
        if (game == null || !userId.equals(game.toActId())) return Optional.empty();
        HoldemGame.Seat seat = game.seatOf(userId);
        int made = -1;
        if (!game.board.isEmpty()) {
            List<String> cards = new ArrayList<>(seat.hole);
            cards.addAll(game.board);
            made = HandEvaluator.best(cards).category().ordinal();
        }
        return Optional.of(new Turn(List.copyOf(seat.hole), List.copyOf(game.board), game.toCall(seat),
                game.pot(), game.minRaiseTo(seat), game.maxRaiseTo(seat), game.canRaise(seat), game.bigBlind, made));
    }

    /** Between hands, whether {@code userId} has a rebuy to decide (for demo bots). */
    @Transactional(readOnly = true)
    public boolean rebuyOffered(UUID betId, UUID userId) {
        HoldemGame game = games.load(betId, HoldemGame.class);
        return game != null && HoldemGame.RESULTS.equals(game.phase) && game.rebuyOffered.contains(userId);
    }

    /**
     * What {@code viewer} may see (domain rules, "Hold'em", "Who sees what"): never the deck, and only your own hole
     * cards while a hand is on. At a showdown everyone still in shows.
     * Stacks, bets, the board and whose turn it is are public, as at a real table. Each hand's shuffle commitment is shown from the deal; once the game is over every secret and every hand is revealed (the audit).
     */
    Map<String, Object> view(UUID betId, UUID viewer) {
        HoldemGame game = games.load(betId, HoldemGame.class);
        Map<String, Object> view = new LinkedHashMap<>();
        if (game == null) {
            view.put("phase", "waiting");
            view.put("players", games.players(betId));
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
}
