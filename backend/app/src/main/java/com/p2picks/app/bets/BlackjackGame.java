package com.p2picks.app.bets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * One hand of Blackjack against an automatic dealer (domain rules, "Blackjack"). Pure state: the service loads it
 * from the round's JSON, applies a deal, decision or timeout, and saves it. Cards are two-character codes: rank
 * {@code A23456789TJQK} then suit {@code SHDC}. Deadlines are epoch milliseconds.
 *
 * <p>Order of play: deal two cards each (the dealer's second face down); if the dealer shows an ace and insurance is
 * on, everyone decides on insurance; the dealer peeks on an ace or ten and ends the hand on a natural; players act
 * (together, or one seat at a time), and anyone who runs out of time stands; then the dealer draws to 17 (hitting a
 * soft 17 if the rules say so) and every hand is paid against the dealer.
 */
final class BlackjackGame {
    enum Action { HIT, STAND, DOUBLE, SPLIT }

    static final String PHASE_INSURANCE = "insurance";
    static final String PHASE_PLAY = "play";
    /** Players are done; the dealer turns the hole card over and draws, one tap at a time. */
    static final String PHASE_DEALER = "dealer";
    static final String PHASE_DONE = "done";

    static final class Hand {
        public List<String> cards = new ArrayList<>();
        public long stake;
        public boolean doubled;
        public boolean fromSplit;
        public boolean done;
        /** After the hand: win, blackjack, push, lose or bust. */
        public String result;
        public long delta;
    }

    static final class Seat {
        public UUID userId;
        public int seat;
        public long bet;
        /** Null until decided (or never offered); 0 when declined. */
        public Long insurance;
        public boolean insuranceOffered;
        public List<Hand> hands = new ArrayList<>();
        public int splits;
        public long deadline;
        public long delta;
    }

    public String phase;
    public List<String> dealer = new ArrayList<>();
    public boolean revealed;
    public List<String> shoe = new ArrayList<>();
    /** Seat order: the index in {@link #seats} whose turn it is; null when everyone acts together or it's over. */
    public Integer turn;
    public List<Seat> seats = new ArrayList<>();
    public long dealerDelta;
    /** When the dealer's turn plays itself out if they don't (epoch ms; 0 outside the dealer's turn). */
    public long dealerDeadline;

    /** A bet waiting for the deal, in seat order. */
    record Buyer(UUID userId, int seat, long bet) {}

    static List<String> shuffledShoe(int decks, Random random) {
        List<String> shoe = new ArrayList<>(decks * 52);
        for (int d = 0; d < decks; d++) {
            for (char suit : "SHDC".toCharArray()) {
                for (char rank : "A23456789TJQK".toCharArray()) shoe.add("" + rank + suit);
            }
        }
        Collections.shuffle(shoe, random);
        return shoe;
    }

    /** Deals a new hand from {@code shoe} (top card first). */
    static BlackjackGame deal(BlackjackRules rules, List<Buyer> buyers, List<String> shoe, long now) {
        if (buyers.isEmpty()) throw new IllegalArgumentException("Nobody to deal to");
        BlackjackGame game = new BlackjackGame();
        game.shoe = new ArrayList<>(shoe);
        for (Buyer buyer : buyers) {
            Seat seat = new Seat();
            seat.userId = buyer.userId();
            seat.seat = buyer.seat();
            seat.bet = buyer.bet();
            Hand hand = new Hand();
            hand.stake = buyer.bet();
            seat.hands.add(hand);
            game.seats.add(seat);
        }
        for (int round = 0; round < 2; round++) {
            for (Seat seat : game.seats) seat.hands.getFirst().cards.add(game.draw());
            game.dealer.add(game.draw());
        }
        if (rules.insurance() && rank(game.dealer.getFirst()) == 'A') {
            game.phase = PHASE_INSURANCE;
            for (Seat seat : game.seats) {
                seat.insuranceOffered = true;
                seat.deadline = now + rules.decisionSec() * 1000L;
            }
        } else {
            game.startPlay(rules, now);
        }
        return game;
    }

    /** The insurance decision: half the bet (rounded down) when taken. Returns the extra stake it adds. */
    long insuranceCost(UUID userId, boolean take) {
        Seat seat = seatOf(userId);
        if (!PHASE_INSURANCE.equals(phase) || seat == null || seat.insurance != null) {
            throw new IllegalStateException("Insurance isn't open for you");
        }
        return take ? seat.bet / 2 : 0;
    }

    void insure(BlackjackRules rules, UUID userId, boolean take, long now) {
        long cost = insuranceCost(userId, take);
        Seat seat = seatOf(userId);
        seat.insurance = cost;
        seat.deadline = 0;
        if (seats.stream().allMatch(s -> s.insurance != null)) startPlay(rules, now);
    }

    /** The extra stake {@code action} would add (a double or split), so the caller can check the points limit. */
    long extraStake(BlackjackRules rules, UUID userId, Action action) {
        Seat seat = requireTurn(userId);
        Hand hand = current(seat);
        return switch (action) {
            case HIT, STAND -> 0;
            case DOUBLE -> {
                if (!canDouble(rules, hand)) throw new IllegalStateException("You can't double this hand");
                yield hand.stake;
            }
            case SPLIT -> {
                if (!canSplit(rules, seat, hand)) throw new IllegalStateException("You can't split this hand");
                yield seat.bet;
            }
        };
    }

    void act(BlackjackRules rules, UUID userId, Action action, long now) {
        extraStake(rules, userId, action);
        Seat seat = seatOf(userId);
        Hand hand = current(seat);
        switch (action) {
            case HIT -> {
                hand.cards.add(draw());
                if (total(hand.cards) >= 21) hand.done = true;
            }
            case STAND -> hand.done = true;
            case DOUBLE -> {
                hand.stake *= 2;
                hand.doubled = true;
                hand.cards.add(draw());
                hand.done = true;
            }
            case SPLIT -> {
                boolean aces = rank(hand.cards.getFirst()) == 'A';
                Hand second = new Hand();
                second.cards.add(hand.cards.removeLast());
                second.stake = seat.bet;
                second.fromSplit = true;
                hand.fromSplit = true;
                seat.hands.add(seat.hands.indexOf(hand) + 1, second);
                seat.splits++;
                hand.cards.add(draw());
                second.cards.add(draw());
                // Split aces get one card each.
                if (aces) {
                    hand.done = true;
                    second.done = true;
                } else {
                    if (total(hand.cards) == 21) hand.done = true;
                    if (total(second.cards) == 21) second.done = true;
                }
            }
        }
        afterDecision(rules, seat, now);
    }

    /** Applies every lapsed deadline: undecided insurance is declined, idle players stand. True if anything changed. */
    boolean timeout(BlackjackRules rules, long now) {
        boolean changed = false;
        if (PHASE_INSURANCE.equals(phase)) {
            for (Seat seat : seats) {
                if (seat.insurance == null && seat.deadline <= now) {
                    seat.insurance = 0L;
                    seat.deadline = 0;
                    changed = true;
                }
            }
            if (changed && seats.stream().allMatch(s -> s.insurance != null)) startPlay(rules, now);
            return changed;
        }
        if (PHASE_DEALER.equals(phase)) {
            // A dealer who doesn't tap in time: the rest of their turn plays itself.
            if (dealerDeadline > now) return false;
            while (PHASE_DEALER.equals(phase)) dealerStep(rules, now);
            return true;
        }
        if (!PHASE_PLAY.equals(phase)) return false;
        for (Seat seat : List.copyOf(seats)) {
            if (seat.deadline == 0 || seat.deadline > now || !PHASE_PLAY.equals(phase)) continue;
            for (Hand hand : seat.hands) hand.done = true;
            changed = true;
            afterDecision(rules, seat, now);
        }
        return changed;
    }

    /** The next moment something lapses, or null. */
    Long nextDeadline() {
        if (PHASE_DONE.equals(phase)) return null;
        if (PHASE_DEALER.equals(phase)) return dealerDeadline;
        return seats.stream().mapToLong(s -> s.deadline).filter(d -> d > 0).boxed().min(Long::compare).orElse(null);
    }

    /** Total stake a seat has at risk now (its hands plus insurance). */
    static long staked(Seat seat) {
        long total = seat.insurance == null ? 0 : seat.insurance;
        for (Hand hand : seat.hands) total = Math.addExact(total, hand.stake);
        return total;
    }

    // ----- Hand values -----

    static char rank(String card) {
        return card.charAt(0);
    }

    static int value(String card) {
        return switch (rank(card)) {
            case 'A' -> 1;
            case 'T', 'J', 'Q', 'K' -> 10;
            default -> rank(card) - '0';
        };
    }

    static int total(List<String> cards) {
        int sum = 0;
        boolean ace = false;
        for (String card : cards) {
            sum += value(card);
            ace |= rank(card) == 'A';
        }
        return ace && sum + 10 <= 21 ? sum + 10 : sum;
    }

    static boolean soft(List<String> cards) {
        int sum = cards.stream().mapToInt(BlackjackGame::value).sum();
        return cards.stream().anyMatch(c -> rank(c) == 'A') && sum + 10 <= 21;
    }

    static boolean natural(List<String> cards) {
        return cards.size() == 2 && total(cards) == 21;
    }

    boolean canDouble(BlackjackRules rules, Hand hand) {
        if (hand.done || hand.cards.size() != 2) return false;
        if (hand.fromSplit && !rules.doubleAfterSplit()) return false;
        if (rules.doubleOn().equals("9-11")) {
            int hard = hand.cards.stream().mapToInt(BlackjackGame::value).sum();
            int best = total(hand.cards);
            return (hard >= 9 && hard <= 11) || (best >= 9 && best <= 11);
        }
        return true;
    }

    boolean canSplit(BlackjackRules rules, Seat seat, Hand hand) {
        if (hand.done || hand.cards.size() != 2 || seat.splits >= rules.maxSplits()) return false;
        // Split aces can't be split again.
        if (hand.fromSplit && rank(hand.cards.getFirst()) == 'A') return false;
        return value(hand.cards.get(0)) == value(hand.cards.get(1));
    }

    // ----- Flow -----

    private void startPlay(BlackjackRules rules, long now) {
        phase = PHASE_PLAY;
        for (Seat seat : seats) seat.deadline = 0;
        int up = value(dealer.getFirst());
        if ((up == 1 || up == 10) && natural(dealer)) {
            for (Seat seat : seats) seat.hands.forEach(h -> h.done = true);
            finish(rules, now);
            return;
        }
        // A player's natural is paid at the end; nothing to decide.
        for (Seat seat : seats) {
            Hand first = seat.hands.getFirst();
            if (natural(first.cards)) first.done = true;
        }
        if (rules.turnOrder().equals("seats")) {
            turn = null;
            advanceTurn(rules, -1, now);
        } else {
            for (Seat seat : seats) {
                if (seat.hands.stream().anyMatch(h -> !h.done)) seat.deadline = now + rules.decisionSec() * 1000L;
            }
            if (seats.stream().allMatch(s -> s.deadline == 0)) finish(rules, now);
        }
    }

    private void afterDecision(BlackjackRules rules, Seat seat, long now) {
        boolean seatDone = seat.hands.stream().allMatch(h -> h.done);
        if (rules.turnOrder().equals("seats")) {
            if (seatDone) {
                seat.deadline = 0;
                advanceTurn(rules, seats.indexOf(seat), now);
            } else {
                seat.deadline = now + rules.decisionSec() * 1000L;
            }
            return;
        }
        seat.deadline = seatDone ? 0 : now + rules.decisionSec() * 1000L;
        if (seats.stream().allMatch(s -> s.hands.stream().allMatch(h -> h.done))) finish(rules, now);
    }

    private void advanceTurn(BlackjackRules rules, int from, long now) {
        for (int i = from + 1; i < seats.size(); i++) {
            Seat next = seats.get(i);
            if (next.hands.stream().anyMatch(h -> !h.done)) {
                turn = i;
                next.deadline = now + rules.decisionSec() * 1000L;
                return;
            }
        }
        turn = null;
        finish(rules, now);
    }

    /**
     * Players are done. With a hand still to beat the dealer takes their turn (flip, then hit or stand, one tap
     * each); otherwise everything is decided and it pays out.
     */
    private void finish(BlackjackRules rules, long now) {
        turn = null;
        for (Seat seat : seats) seat.deadline = 0;
        boolean anyLive = seats.stream().flatMap(s -> s.hands.stream())
                .anyMatch(h -> total(h.cards) <= 21 && !(natural(h.cards) && !h.fromSplit));
        if (!natural(dealer) && anyLive) {
            phase = PHASE_DEALER;
            dealerDeadline = now + rules.decisionSec() * 1000L;
            return;
        }
        settle(rules);
    }

    /** The dealer's one move right now: flip the hole card, then hit while the rules say to, then stand (a bust pays
     * out by itself). */
    String dealerMove(BlackjackRules rules) {
        if (!PHASE_DEALER.equals(phase)) return null;
        if (!revealed) return "flip";
        int total = total(dealer);
        return total < 17 || (total == 17 && rules.dealerHitsSoft17() && soft(dealer)) ? "hit" : "stand";
    }

    /** One tap of the dealer's button. */
    void dealerStep(BlackjackRules rules, long now) {
        String move = dealerMove(rules);
        if (move == null) throw new IllegalStateException("It isn't the dealer's turn");
        switch (move) {
            case "flip" -> revealed = true;
            case "hit" -> {
                dealer.add(draw());
                // A bust is over without another tap.
                if (total(dealer) > 21) {
                    settle(rules);
                    return;
                }
            }
            default -> {
                settle(rules);
                return;
            }
        }
        dealerDeadline = now + rules.decisionSec() * 1000L;
    }

    /** Pays every hand against the dealer's final cards. */
    private void settle(BlackjackRules rules) {
        turn = null;
        revealed = true;
        dealerDeadline = 0;
        for (Seat seat : seats) seat.deadline = 0;
        boolean dealerNatural = natural(dealer);
        int dealerTotal = total(dealer);
        long dealerSum = 0;
        for (Seat seat : seats) {
            long seatDelta = 0;
            for (Hand hand : seat.hands) {
                int mine = total(hand.cards);
                boolean myNatural = natural(hand.cards) && !hand.fromSplit;
                if (dealerNatural) {
                    hand.result = myNatural ? "push" : "lose";
                    hand.delta = myNatural ? 0 : -hand.stake;
                } else if (myNatural) {
                    hand.result = "blackjack";
                    hand.delta = rules.blackjackPays().equals("3:2") ? hand.stake * 3 / 2 : hand.stake * 6 / 5;
                } else if (mine > 21) {
                    hand.result = "bust";
                    hand.delta = -hand.stake;
                } else if (dealerTotal > 21 || mine > dealerTotal) {
                    hand.result = "win";
                    hand.delta = hand.stake;
                } else if (mine == dealerTotal) {
                    hand.result = "push";
                    hand.delta = 0;
                } else {
                    hand.result = "lose";
                    hand.delta = -hand.stake;
                }
                hand.done = true;
                seatDelta = Math.addExact(seatDelta, hand.delta);
            }
            if (seat.insurance != null && seat.insurance > 0) {
                seatDelta = Math.addExact(seatDelta, dealerNatural ? seat.insurance * 2 : -seat.insurance);
            }
            seat.delta = seatDelta;
            dealerSum = Math.addExact(dealerSum, seatDelta);
        }
        dealerDelta = -dealerSum;
        phase = PHASE_DONE;
    }

    private static final Random RESHUFFLE = new java.security.SecureRandom();

    /** Takes the top card; a shoe that runs out (one deck, a full table splitting) gets a fresh shuffled deck. */
    private String draw() {
        if (shoe.isEmpty()) shoe.addAll(shuffledShoe(1, RESHUFFLE));
        return shoe.removeFirst();
    }

    private Seat seatOf(UUID userId) {
        return seats.stream().filter(s -> s.userId.equals(userId)).findFirst().orElse(null);
    }

    private Seat requireTurn(UUID userId) {
        Seat seat = seatOf(userId);
        if (!PHASE_PLAY.equals(phase) || seat == null || seat.hands.stream().allMatch(h -> h.done)) {
            throw new IllegalStateException("It isn't your turn");
        }
        if (turn != null && seats.get(turn) != seat) throw new IllegalStateException("It isn't your turn");
        return seat;
    }

    private static Hand current(Seat seat) {
        return seat.hands.stream().filter(h -> !h.done).findFirst().orElseThrow();
    }
}
