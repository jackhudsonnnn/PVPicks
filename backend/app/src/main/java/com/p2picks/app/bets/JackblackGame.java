package com.p2picks.app.bets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * One Jackblack ticket (domain rules, "Jackblack"): every player deals once, in a random order, and the
 * round's dealer banks it against everyone else. Pure state: the service loads it from the round's JSON, applies a
 * decision, a dealer tap or a timeout, and saves it. Cards are two-character codes: rank {@code A23456789TJQK} then
 * suit {@code SHDC}. Deadlines are epoch milliseconds.
 *
 * <p>A round: a fresh shared shoe; two cards each, the dealer's second face down; the dealer peeks on an ace or ten
 * and a dealer natural ends the round (player naturals push, the rest lose). Then players act in <b>lockstep</b>:
 * everyone still deciding picks hit, stand, double or split for their current hand, and only once all have chosen,
 * or the decision timer runs out (they stand), are the new cards dealt and shown. When nobody has a hand left to
 * play, the dealer turns the hole card over and draws to 17 with one forced button (a bust pays out by itself; the
 * timer plays it out). Each hand is paid against the dealer: win 1:1, a natural 3:2 or 6:5 (rounded down), push 0,
 * lose or bust the stake.
 */
final class JackblackGame implements CardGames.State {
    enum Action { HIT, STAND, DOUBLE, SPLIT }

    static final String PLAY = "play";
    static final String DEALER = "dealer";
    static final String DONE = "done";
    /** Between rounds: the finished round's results stay up for a moment before the next deal. */
    static final String RESULTS = "results";
    /** How long a finished round's results stay up (owner decision, 2026-11-22). */
    static final long ROUND_PAUSE_MS = 4000;

    static final class Hand {
        public List<String> cards = new ArrayList<>();
        public long stake;
        public boolean doubled;
        public boolean fromSplit;
        public boolean done;
        /** After the round: win, blackjack, push, lose or bust. */
        public String result;
        public long delta;
    }

    static final class Seat {
        public UUID userId;
        public List<Hand> hands = new ArrayList<>();
        public int splits;
        /** This step's choice, hidden until everyone has chosen; null while undecided. */
        public String pending;
        public long delta;
    }

    /** A finished round, kept for the ticket's history. */
    static final class Round {
        public int number;
        public UUID dealerId;
        public List<String> dealer = new ArrayList<>();
        public List<Seat> seats = new ArrayList<>();
        public long dealerDelta;
    }

    public long ante;
    public List<UUID> order = new ArrayList<>();
    public int round;
    public int rounds;
    public String phase;
    public List<String> shoe = new ArrayList<>();
    public List<String> dealer = new ArrayList<>();
    public boolean revealed;
    public List<Seat> seats = new ArrayList<>();
    /** When this step (or the dealer's turn) times out. */
    public long deadline;
    public Map<UUID, Long> totals = new LinkedHashMap<>();
    /** Each round's shoe secret, fixed at the deal and revealed only when the game is over (CardShuffle). */
    public List<CardShuffle.Secret> secrets = new ArrayList<>();
    /** How many times this round's shoe has run out (it continues from the same secret). */
    public int pass;
    public List<Round> history = new ArrayList<>();

    UUID dealerId() {
        return order.get(round % order.size());
    }

    /** Starts the ticket: the dealing order is shuffled, and the first round is dealt from {@code shoes}. */
    static JackblackGame start(JackblackRules rules, List<UUID> players, long ante, Random random, Shoes shoes,
            long now) {
        if (players.size() < JackblackRules.MIN_PLAYERS) throw new IllegalArgumentException("Not enough players");
        JackblackGame game = new JackblackGame();
        game.ante = ante;
        game.order = new ArrayList<>(players);
        Collections.shuffle(game.order, random);
        game.rounds = game.order.size();
        for (UUID player : game.order) game.totals.put(player, 0L);
        // Every round's shoe is fixed now (a random deck count and seed), so its commitment can be shown up front.
        for (int r = 1; r <= game.rounds; r++) game.secrets.add(CardShuffle.draw(r, random));
        game.startRound(rules, shoes, now);
        return game;
    }

    /** Where a round's shoe comes from: its secret's shuffle (tests stack it). */
    interface Shoes {
        List<String> next(CardShuffle.Secret secret);
    }

    static final Shoes FROM_SECRETS = secret -> CardShuffle.cards(secret, 0);

    // ----- Decisions -----

    /** Whether {@code userId} still has a choice to make this step. */
    boolean deciding(UUID userId) {
        Seat seat = seatOf(userId);
        return PLAY.equals(phase) && seat != null && seat.pending == null && current(seat) != null;
    }

    /** Records a player's choice for this step; once everyone still playing has chosen, the cards come out. */
    void choose(JackblackRules rules, UUID userId, Action action, Shoes shoes, long now) {
        Seat seat = seatOf(userId);
        if (!deciding(userId)) throw new NotAllowed("You've no choice to make right now");
        Hand hand = current(seat);
        if (action == Action.DOUBLE && !canDouble(rules, hand)) {
            throw new NotAllowed("You can't double this hand");
        }
        if (action == Action.SPLIT && !canSplit(rules, seat, hand)) {
            throw new NotAllowed("You can't split this hand");
        }
        seat.pending = action.name();
        if (seats.stream().filter(s -> current(s) != null).allMatch(s -> s.pending != null)) {
            resolveStep(rules, shoes, now);
        }
    }

    /** The dealer's one move on their turn: flip the hole card, then hit while under 17 (soft 17 stands), then stand. */
    String dealerMove() {
        if (!DEALER.equals(phase)) return null;
        if (!revealed) return "flip";
        return total(dealer) < 17 ? "hit" : "stand";
    }

    /** One tap of the dealer's button. */
    void dealerStep(JackblackRules rules, Shoes shoes, long now) {
        String move = dealerMove();
        if (move == null) throw new NotAllowed("It isn't the dealer's turn");
        switch (move) {
            case "flip" -> revealed = true;
            case "hit" -> {
                dealer.add(draw());
                if (total(dealer) > 21) {
                    settleRound(rules, shoes, now);
                    return;
                }
            }
            default -> {
                settleRound(rules, shoes, now);
                return;
            }
        }
        deadline = now + rules.decisionSec() * 1000L;
    }

    /** Applies a lapsed timer: undecided players stand; an idle dealer's turn plays itself out. True if it moved on. */
    boolean timeout(JackblackRules rules, Shoes shoes, long now) {
        if (DONE.equals(phase) || deadline == 0 || deadline > now) return false;
        if (PLAY.equals(phase)) {
            resolveStep(rules, shoes, now);
        } else if (RESULTS.equals(phase)) {
            startRound(rules, shoes, now);
        } else {
            int started = round;
            while (DEALER.equals(phase) && round == started) dealerStep(rules, shoes, now);
        }
        return true;
    }

    /** The next moment something lapses, or null when it's over. */
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
        int sum = cards.stream().mapToInt(JackblackGame::value).sum();
        return cards.stream().anyMatch(c -> rank(c) == 'A') && sum + 10 <= 21;
    }

    static boolean natural(List<String> cards) {
        return cards.size() == 2 && total(cards) == 21;
    }

    boolean canDouble(JackblackRules rules, Hand hand) {
        return rules.doubling() && !hand.done && hand.cards.size() == 2;
    }

    boolean canSplit(JackblackRules rules, Seat seat, Hand hand) {
        if (hand.done || hand.cards.size() != 2 || seat.splits >= rules.maxSplits()) return false;
        if (hand.fromSplit && rank(hand.cards.getFirst()) == 'A') return false;
        return value(hand.cards.get(0)) == value(hand.cards.get(1));
    }

    static Hand current(Seat seat) {
        return seat.hands.stream().filter(h -> !h.done).findFirst().orElse(null);
    }

    Seat seatOf(UUID userId) {
        return seats.stream().filter(s -> s.userId.equals(userId)).findFirst().orElse(null);
    }

    // ----- Flow -----

    private void startRound(JackblackRules rules, Shoes shoes, long now) {
        pass = 0;
        shoe = new ArrayList<>(shoes.next(secrets.get(round)));
        dealer = new ArrayList<>();
        revealed = false;
        seats = new ArrayList<>();
        UUID dealing = dealerId();
        // Seats in the dealing order, starting after the dealer.
        for (int i = 1; i < order.size(); i++) {
            Seat seat = new Seat();
            seat.userId = order.get((round + i) % order.size());
            Hand hand = new Hand();
            hand.stake = ante;
            seat.hands.add(hand);
            seats.add(seat);
        }
        if (seats.stream().anyMatch(s -> s.userId.equals(dealing))) throw new IllegalStateException("Dealer seated");
        for (int pass = 0; pass < 2; pass++) {
            for (Seat seat : seats) seat.hands.getFirst().cards.add(draw());
            dealer.add(draw());
        }
        phase = PLAY;
        int up = value(dealer.getFirst());
        if ((up == 1 || up == 10) && natural(dealer)) {
            for (Seat seat : seats) seat.hands.forEach(h -> h.done = true);
            settleRound(rules, shoes, now);
            return;
        }
        for (Seat seat : seats) {
            Hand first = seat.hands.getFirst();
            if (natural(first.cards)) first.done = true;
        }
        afterStep(rules, shoes, now);
    }

    /** Deals every pending choice at once, in seat order; anyone who hadn't chosen stands. */
    private void resolveStep(JackblackRules rules, Shoes shoes, long now) {
        for (Seat seat : seats) {
            Hand hand = current(seat);
            if (hand == null) continue;
            Action action = seat.pending == null ? Action.STAND : Action.valueOf(seat.pending);
            seat.pending = null;
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
                    second.stake = ante;
                    second.fromSplit = true;
                    hand.fromSplit = true;
                    seat.hands.add(seat.hands.indexOf(hand) + 1, second);
                    seat.splits++;
                    hand.cards.add(draw());
                    second.cards.add(draw());
                    if (aces) {
                        hand.done = true;
                        second.done = true;
                    } else {
                        if (total(hand.cards) == 21) hand.done = true;
                        if (total(second.cards) == 21) second.done = true;
                    }
                }
            }
        }
        afterStep(rules, shoes, now);
    }

    private void afterStep(JackblackRules rules, Shoes shoes, long now) {
        if (seats.stream().anyMatch(s -> current(s) != null)) {
            deadline = now + rules.decisionSec() * 1000L;
            return;
        }
        boolean live = seats.stream().flatMap(s -> s.hands.stream())
                .anyMatch(h -> total(h.cards) <= 21 && !(natural(h.cards) && !h.fromSplit));
        if (live) {
            phase = DEALER;
            deadline = now + rules.decisionSec() * 1000L;
        } else {
            settleRound(rules, shoes, now);
        }
    }

    private void settleRound(JackblackRules rules, Shoes shoes, long now) {
        revealed = true;
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
            seat.delta = seatDelta;
            totals.merge(seat.userId, seatDelta, Math::addExact);
            dealerSum = Math.addExact(dealerSum, seatDelta);
        }
        UUID dealing = dealerId();
        totals.merge(dealing, -dealerSum, Math::addExact);
        Round finished = new Round();
        finished.number = round + 1;
        finished.dealerId = dealing;
        finished.dealer = List.copyOf(dealer);
        finished.seats = seats;
        finished.dealerDelta = -dealerSum;
        history.add(finished);
        round++;
        if (round >= rounds) {
            phase = DONE;
            deadline = 0;
        } else {
            // Everyone sees how the round went before the next one deals.
            phase = RESULTS;
            deadline = now + ROUND_PAUSE_MS;
        }
    }

    /** Takes the top card; a shoe that runs out continues with the next pass of the same secret. */
    private String draw() {
        if (shoe.isEmpty()) shoe.addAll(CardShuffle.cards(secrets.get(round), ++pass));
        return shoe.removeFirst();
    }
}
