package com.p2picks.app.bets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * One Hold'em game, the pure rules with no storage (domain rules, "Hold'em"): no-limit Texas hold'em over one
 * full cycle, a hand for each player, with the button moving one seat a hand. Stacks carry from hand to hand; a player who
 * busts can take one rebuy, right then. A player's result is their final stack minus what they bought in for, so the
 * game is zero-sum. Public fields so the state is stored as JSON; chips are cents of the wager (or play chips in a
 * 4fun game).
 */
final class HoldemGame implements CardGames.State {
    static final String PLAY = "play";
    /** Between hands: who won, shown hands, and the busted player's rebuy choice. */
    static final String RESULTS = "results";
    static final String DONE = "done";

    static final String PREFLOP = "preflop", FLOP = "flop", TURN = "turn", RIVER = "river";

    enum Action { FOLD, CHECK, CALL, RAISE, ALL_IN }

    /**
     * A move armed before your turn, played the moment the action reaches you: CHECK_FOLD checks if there's nothing
     * to call and folds otherwise; CALL_ANY checks or calls whatever the bet is. It lasts one decision, this hand.
     */
    enum PreMove { CHECK_FOLD, CALL_ANY }

    static final class Seat {
        public UUID userId;
        public List<String> hole = new ArrayList<>();
        /** Put in on this street. */
        public long committed;
        /** Put in over the whole hand (for the pots). */
        public long contributed;
        public boolean folded;
        public boolean allIn;
        /** Has acted since the last full raise. */
        public boolean acted;
        /** A short all-in reopened the action, but only to call or fold: no raise. */
        public boolean capped;
        /** Armed for this player's next decision (only they see it). */
        public PreMove preMove;
    }

    static final class Pot {
        public long amount;
        public List<UUID> eligible = new ArrayList<>();
        public List<UUID> winners = new ArrayList<>();
        /** The winning hand's name at a showdown, or null when everyone else folded. */
        public String handName;
    }

    static final class Hand {
        public int number;
        public UUID buttonId;
        public UUID smallBlindId;
        public UUID bigBlindId;
        public List<String> board = new ArrayList<>();
        public List<Pot> pots = new ArrayList<>();
        /** Whether it went to a showdown (more than one player left after the river). */
        public boolean showdown;
        /** Hole cards shown: everyone still in at a showdown (no mucking: the audit reveals every hand anyway). */
        public Map<UUID, List<String>> shown = new LinkedHashMap<>();
        /** The best hand of everyone who reached the showdown. */
        public Map<UUID, String> handNames = new LinkedHashMap<>();
        /** Everyone's hole cards: never sent until the game is over (then they're auditable anyway). */
        public Map<UUID, List<String>> holes = new LinkedHashMap<>();
        /** Players still in at the showdown. */
        public List<UUID> atShowdown = new ArrayList<>();
        /** Each player's stack change over the hand. */
        public Map<UUID, Long> deltas = new LinkedHashMap<>();
    }

    public long bigBlind;
    public long smallBlind;
    public long buyIn;
    /** Seating order, shuffled at the deal; the button starts on the first seat and moves one a hand. */
    public List<UUID> order = new ArrayList<>();
    public int hands;
    /** Hands started so far (the current hand's number while one is on). */
    public int hand;
    /** Index into {@link #order} of the current (or last) button. */
    public int button = -1;
    public Map<UUID, Long> stacks = new LinkedHashMap<>();
    public Map<UUID, Integer> buyIns = new LinkedHashMap<>();
    /** Busted for good (no rebuy left, or let it pass). */
    public List<UUID> out = new ArrayList<>();
    /** Busted this hand with a rebuy left, still deciding (only between hands). */
    public List<UUID> rebuyOffered = new ArrayList<>();
    public List<CardShuffle.Secret> secrets = new ArrayList<>();
    public List<String> deck = new ArrayList<>();
    public int pass;
    public String phase;
    public long deadline;
    /** When the current pause between hands began. */
    public long pausedAt;

    public List<Seat> seats = new ArrayList<>();
    public List<String> board = new ArrayList<>();
    public String street;
    public long currentBet;
    public long minRaise;
    /** Index into {@link #seats} of whoever acts next, or −1. */
    public int toAct = -1;
    public UUID buttonId;
    public UUID smallBlindId;
    public UUID bigBlindId;
    public Map<UUID, Long> stackAtStart = new LinkedHashMap<>();

    public List<Hand> history = new ArrayList<>();
    /** Each player's result once the game is over: final stack minus their buy-ins. */
    public Map<UUID, Long> totals = new LinkedHashMap<>();

    /** Where a hand's deck comes from: its secret's shuffle (tests stack it). */
    interface Decks {
        List<String> next(CardShuffle.Secret secret);
    }

    static final Decks FROM_SECRETS = secret -> CardShuffle.cards(secret, 0);

    // ----- Starting -----

    static HoldemGame start(HoldemRules rules, List<UUID> players, long bigBlind, Random random, Decks decks,
            long now) {
        if (players.size() < HoldemRules.MIN_PLAYERS) throw new IllegalArgumentException("Not enough players");
        HoldemGame game = new HoldemGame();
        game.bigBlind = bigBlind;
        game.smallBlind = bigBlind / 2;
        game.buyIn = HoldemRules.buyIn(bigBlind);
        game.order = new ArrayList<>(players);
        Collections.shuffle(game.order, random);
        game.hands = game.order.size();
        for (UUID player : game.order) {
            game.stacks.put(player, game.buyIn);
            game.buyIns.put(player, 1);
        }
        // Every hand's deck is fixed now (one deck, a random seed), so its commitment can be shown up front.
        for (int h = 1; h <= game.hands; h++) game.secrets.add(CardShuffle.draw(h, 1, random));
        game.startHand(rules, decks, now);
        return game;
    }

    /** Players still playing: not out, with chips. */
    List<UUID> active() {
        List<UUID> active = new ArrayList<>();
        for (UUID player : order) if (!out.contains(player) && stacks.get(player) > 0) active.add(player);
        return active;
    }

    private void startHand(HoldemRules rules, Decks decks, long now) {
        List<UUID> active = active();
        if (hand >= hands || active.size() < HoldemRules.MIN_PLAYERS) {
            finish();
            return;
        }
        // The button moves to the next player still in (the first seat for the first hand).
        int next = button < 0 ? 0 : (button + 1) % order.size();
        while (!active.contains(order.get(next))) next = (next + 1) % order.size();
        button = next;
        buttonId = order.get(button);
        seats = new ArrayList<>();
        for (int i = 1; i <= order.size(); i++) {
            UUID player = order.get((button + i) % order.size());
            if (!active.contains(player)) continue;
            Seat seat = new Seat();
            seat.userId = player;
            seats.add(seat);
        }
        stackAtStart = new LinkedHashMap<>();
        for (UUID player : active) stackAtStart.put(player, stacks.get(player));
        pass = 0;
        deck = new ArrayList<>(decks.next(secrets.get(hand)));
        hand++;
        board = new ArrayList<>();
        for (int round = 0; round < 2; round++) for (Seat seat : seats) seat.hole.add(draw());
        // Heads-up the button is the small blind and acts first before the flop; otherwise the two after it.
        boolean headsUp = seats.size() == 2;
        Seat sb = headsUp ? seats.get(1) : seats.get(0);
        Seat bb = headsUp ? seats.get(0) : seats.get(1);
        smallBlindId = sb.userId;
        bigBlindId = bb.userId;
        put(sb, Math.min(smallBlind, stacks.get(sb.userId)));
        put(bb, Math.min(bigBlind, stacks.get(bb.userId)));
        street = PREFLOP;
        currentBet = bigBlind;
        minRaise = bigBlind;
        phase = PLAY;
        int first = headsUp ? 1 : 2 % seats.size();
        toAct = -1;
        nextToAct(first - 1, rules, decks, now);
    }

    // ----- Acting -----

    Seat seatOf(UUID userId) {
        for (Seat seat : seats) if (seat.userId.equals(userId)) return seat;
        return null;
    }

    UUID toActId() {
        return PLAY.equals(phase) && toAct >= 0 ? seats.get(toAct).userId : null;
    }

    long toCall(Seat seat) {
        return Math.max(0, Math.min(currentBet - seat.committed, stacks.get(seat.userId)));
    }

    /** The smallest raise-to amount allowed (an all-in for less is always allowed). */
    long minRaiseTo(Seat seat) {
        return Math.min(seat.committed + stacks.get(seat.userId), currentBet + minRaise);
    }

    long maxRaiseTo(Seat seat) {
        return seat.committed + stacks.get(seat.userId);
    }

    boolean canRaise(Seat seat) {
        return !seat.capped && stacks.get(seat.userId) > currentBet - seat.committed;
    }

    /** {@code amount} is the raise-to total for this street (only for RAISE). */
    void act(HoldemRules rules, UUID userId, Action action, long amount, Decks decks, long now) {
        if (!userId.equals(toActId())) throw new NotAllowed("It isn't your turn");
        Seat seat = seats.get(toAct);
        long owed = currentBet - seat.committed;
        switch (action) {
            case FOLD -> seat.folded = true;
            case CHECK -> {
                if (owed > 0) throw new NotAllowed("You can't check: there's a bet to call");
            }
            case CALL -> {
                if (owed <= 0) throw new NotAllowed("There's nothing to call");
                put(seat, Math.min(owed, stacks.get(userId)));
            }
            case ALL_IN -> {
                long all = maxRaiseTo(seat);
                if (all > currentBet && canRaise(seat)) raiseTo(seat, all);
                else put(seat, stacks.get(userId));
            }
            case RAISE -> {
                if (!canRaise(seat)) throw new NotAllowed("You can only call or fold here");
                long max = maxRaiseTo(seat);
                long to = Math.min(amount, max);
                if (to <= currentBet) throw new NotAllowed("A raise has to be more than the current bet");
                if (to < currentBet + minRaise && to < max) {
                    throw new NotAllowed("The smallest raise is to " + (currentBet + minRaise));
                }
                raiseTo(seat, to);
            }
        }
        seat.acted = true;
        if (stacks.get(userId) == 0 && !seat.folded) seat.allIn = true;
        nextToAct(toAct, rules, decks, now);
    }

    /** Arms (or with {@code null} clears) a pre-move while it isn't your turn. */
    void preMove(UUID userId, PreMove move) {
        Seat seat = PLAY.equals(phase) ? seatOf(userId) : null;
        if (seat == null || !canAct(seat)) throw new NotAllowed("You aren't in this hand");
        if (userId.equals(toActId())) throw new NotAllowed("It's your turn: make your move");
        seat.preMove = move;
    }

    private void raiseTo(Seat seat, long to) {
        long increase = to - currentBet;
        put(seat, to - seat.committed);
        boolean full = increase >= minRaise;
        if (full) minRaise = increase;
        currentBet = to;
        for (Seat other : seats) {
            if (other == seat || !canAct(other)) continue;
            if (full) {
                other.acted = false;
                other.capped = false;
            } else if (other.acted) {
                // A short all-in: whoever already acted must answer it, but can't raise again.
                other.acted = false;
                other.capped = true;
            }
        }
    }

    private void put(Seat seat, long chips) {
        stacks.merge(seat.userId, -chips, Long::sum);
        seat.committed += chips;
        seat.contributed += chips;
        if (stacks.get(seat.userId) == 0) seat.allIn = true;
    }

    private static boolean canAct(Seat seat) {
        return !seat.folded && !seat.allIn;
    }

    private boolean bettingDone() {
        long acting = seats.stream().filter(HoldemGame::canAct).count();
        if (acting == 0) return true;
        if (acting == 1) {
            // Nobody left to bet against: done once the last player has matched the bet.
            Seat last = seats.stream().filter(HoldemGame::canAct).findFirst().orElseThrow();
            if (last.committed >= currentBet) return true;
        }
        for (Seat seat : seats) if (canAct(seat) && (!seat.acted || seat.committed < currentBet)) return false;
        return true;
    }

    /** Moves the action on from seat {@code from}: the next player due to act, a new street, or the end. */
    private void nextToAct(int from, HoldemRules rules, Decks decks, long now) {
        if (seats.stream().filter(s -> !s.folded).count() == 1) {
            endHand(rules, now);
            return;
        }
        if (bettingDone()) {
            nextStreet(rules, decks, now);
            return;
        }
        for (int i = 1; i <= seats.size(); i++) {
            int index = (from + i + seats.size()) % seats.size();
            Seat seat = seats.get(index);
            if (canAct(seat) && (!seat.acted || seat.committed < currentBet)) {
                toAct = index;
                deadline = now + rules.decisionSec() * 1000L;
                if (seat.preMove != null) {
                    PreMove armed = seat.preMove;
                    seat.preMove = null;
                    act(rules, seat.userId, toCall(seat) == 0 ? Action.CHECK
                            : armed == PreMove.CALL_ANY ? Action.CALL : Action.FOLD, 0, decks, now);
                }
                return;
            }
        }
        nextStreet(rules, decks, now);
    }

    private void nextStreet(HoldemRules rules, Decks decks, long now) {
        if (RIVER.equals(street)) {
            endHand(rules, now);
            return;
        }
        for (Seat seat : seats) {
            seat.committed = 0;
            seat.acted = false;
            seat.capped = false;
        }
        currentBet = 0;
        minRaise = bigBlind;
        switch (street) {
            case PREFLOP -> {
                street = FLOP;
                for (int i = 0; i < 3; i++) board.add(draw());
            }
            case FLOP -> {
                street = TURN;
                board.add(draw());
            }
            default -> {
                street = RIVER;
                board.add(draw());
            }
        }
        // After the flop the first player still in after the button acts first (seat 0 onwards).
        if (seats.stream().filter(HoldemGame::canAct).count() <= 1) {
            // Everyone else is all in: the rest of the board comes out with no more betting.
            nextStreet(rules, decks, now);
            return;
        }
        nextToAct(-1, rules, decks, now);
    }

    // ----- The end of a hand -----

    private void endHand(HoldemRules rules, long now) {
        toAct = -1;
        List<Seat> live = seats.stream().filter(s -> !s.folded).toList();
        boolean showdown = live.size() > 1;
        Hand record = new Hand();
        record.number = hand;
        record.buttonId = buttonId;
        record.smallBlindId = smallBlindId;
        record.bigBlindId = bigBlindId;
        record.board = List.copyOf(board);
        record.showdown = showdown;
        for (Seat seat : seats) record.holes.put(seat.userId, List.copyOf(seat.hole));
        Map<UUID, HandEvaluator.Hand> best = new LinkedHashMap<>();
        if (showdown) {
            for (Seat seat : live) {
                List<String> seven = new ArrayList<>(seat.hole);
                seven.addAll(board);
                best.put(seat.userId, HandEvaluator.best(seven));
                record.atShowdown.add(seat.userId);
            }
        }
        record.pots = pots();
        int potIndex = 0;
        for (Pot pot : record.pots) {
            if (showdown) {
                long top = pot.eligible.stream().mapToLong(id -> best.get(id).score()).max().orElseThrow();
                for (UUID id : pot.eligible) if (best.get(id).score() == top) pot.winners.add(id);
                pot.handName = best.get(pot.winners.getFirst()).name();
            } else {
                pot.winners.add(live.getFirst().userId);
            }
            pay(pot, potIndex++);
        }
        // Everyone still in shows; there's no mucking, since the audit reveals every hand after the game anyway.
        if (showdown) {
            for (Seat seat : live) {
                record.shown.put(seat.userId, record.holes.get(seat.userId));
                record.handNames.put(seat.userId, best.get(seat.userId).name());
            }
        }
        for (Map.Entry<UUID, Long> start : stackAtStart.entrySet()) {
            record.deltas.put(start.getKey(), stacks.get(start.getKey()) - start.getValue());
        }
        history.add(record);

        // Busted: a rebuy if there's one left and another hand to play it in; otherwise out for good.
        boolean more = hand < hands;
        rebuyOffered = new ArrayList<>();
        for (Seat seat : seats) {
            if (stacks.get(seat.userId) > 0) continue;
            if (more && buyIns.get(seat.userId) < HoldemRules.MAX_BUY_INS) rebuyOffered.add(seat.userId);
            else if (!out.contains(seat.userId)) out.add(seat.userId);
        }
        phase = RESULTS;
        pausedAt = now;
        deadline = now + (rebuyOffered.isEmpty() ? HoldemRules.HAND_PAUSE_MS : HoldemRules.REBUY_MS);
    }

    /** Main pot and side pots from what everyone put in; folded chips count but folded players can't win. */
    private List<Pot> pots() {
        List<Long> levels = seats.stream().map(s -> s.contributed).filter(c -> c > 0).distinct().sorted().toList();
        List<Pot> pots = new ArrayList<>();
        long previous = 0;
        for (long level : levels) {
            Pot pot = new Pot();
            for (Seat seat : seats) {
                pot.amount += Math.min(seat.contributed, level) - Math.min(seat.contributed, previous);
                if (!seat.folded && seat.contributed >= level) pot.eligible.add(seat.userId);
            }
            previous = level;
            if (pot.amount == 0) continue;
            Pot last = pots.isEmpty() ? null : pots.getLast();
            if (pot.eligible.isEmpty() && last != null) {
                last.amount += pot.amount;
            } else if (last != null && last.eligible.equals(pot.eligible)) {
                last.amount += pot.amount;
            } else {
                pots.add(pot);
            }
        }
        return pots;
    }

    /** Splits a pot evenly; chips that don't divide go one each to winners in a random order (neutral ties). */
    private void pay(Pot pot, int potIndex) {
        long share = pot.amount / pot.winners.size();
        long left = pot.amount % pot.winners.size();
        List<UUID> order = new ArrayList<>(pot.winners);
        order.sort(Comparator.comparing(UUID::toString));
        Collections.shuffle(order, new Random(seed(potIndex)));
        for (int i = 0; i < order.size(); i++) {
            stacks.merge(order.get(i), share + (i < left ? 1 : 0), Long::sum);
        }
    }

    /** Fixed by the hand's secret, so a replay splits the same way. */
    private long seed(int potIndex) {
        String hash = CardShuffle.sha256Hex(secrets.get(hand - 1).seed + "|pot|" + potIndex);
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    // ----- Between hands -----

    /** A busted player's one rebuy (or letting it pass). */
    void rebuy(UUID userId, boolean take, long now) {
        if (!RESULTS.equals(phase) || !rebuyOffered.contains(userId)) throw new NotAllowed("There's no rebuy to take");
        rebuyOffered.remove(userId);
        if (take) {
            stacks.merge(userId, buyIn, Long::sum);
            buyIns.merge(userId, 1, Integer::sum);
        } else {
            out.add(userId);
        }
        // Once nobody's left deciding, the usual pause is enough.
        if (rebuyOffered.isEmpty()) deadline = Math.max(now, Math.min(deadline, pausedAt + HoldemRules.HAND_PAUSE_MS));
    }

    /** Applies a lapsed timer: whoever's turn it is checks if they can, otherwise folds; a pause ends. */
    boolean timeout(HoldemRules rules, Decks decks, long now) {
        if (DONE.equals(phase) || deadline == 0 || deadline > now) return false;
        if (PLAY.equals(phase)) {
            Seat seat = seats.get(toAct);
            act(rules, seat.userId, toCall(seat) == 0 ? Action.CHECK : Action.FOLD, 0, decks, now);
        } else {
            for (UUID undecided : List.copyOf(rebuyOffered)) rebuy(undecided, false, now);
            startHand(rules, decks, now);
        }
        return true;
    }

    @Override
    public boolean over() {
        return DONE.equals(phase);
    }

    @Override
    public Map<UUID, Long> results() {
        return totals;
    }

    @Override
    public Long nextDeadline() {
        return DONE.equals(phase) || deadline == 0 ? null : deadline;
    }

    private void finish() {
        phase = DONE;
        deadline = 0;
        toAct = -1;
        totals = new LinkedHashMap<>();
        for (UUID player : order) {
            totals.put(player, stacks.get(player) - Math.multiplyExact(buyIn, buyIns.get(player)));
        }
    }

    /** All the pots on the table right now: what's been put in this hand (nothing between hands, once it's paid). */
    long pot() {
        return PLAY.equals(phase) ? seats.stream().mapToLong(s -> s.contributed).sum() : 0;
    }

    private String draw() {
        if (deck.isEmpty()) deck.addAll(CardShuffle.cards(secrets.get(hand - 1), ++pass));
        return deck.removeFirst();
    }
}
