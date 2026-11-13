package com.p2picks.app.bets;

import com.p2picks.shared.kernel.Money;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PayoutCalculator {
    public enum RemainderMode { HOST, PROPOSER, EARLIEST, RANDOM }

    public enum WashReason { INVALID_OUTCOME, INSUFFICIENT_PLAYERS, SAME_SIDE, NO_WINNER }

    public record Participant(UUID userId, String choice) {
        public Participant {
            Objects.requireNonNull(userId, "userId");
        }
    }

    public record Remainder(RemainderMode mode, UUID recipientId) {
        public Remainder {
            Objects.requireNonNull(mode, "mode");
            boolean needsRecipient = mode == RemainderMode.HOST || mode == RemainderMode.PROPOSER;
            if (needsRecipient != (recipientId != null)) {
                throw new IllegalArgumentException("Host and proposer modes require a recipient; other modes do not");
            }
        }
    }

    public sealed interface Result {
        record Resolved(Map<UUID, Money> deltas) implements Result {
            public Resolved {
                deltas = Map.copyOf(deltas);
            }
        }

        record Washed(WashReason reason) implements Result {}
    }

    private PayoutCalculator() {}

    // Participants are in first-participation order; a null choice represents No guess.
    public static Result settle(
            UUID betId, List<Participant> participants, Money wager, boolean forFun,
            List<String> choices, String winningChoice, Remainder remainder) {
        Objects.requireNonNull(betId, "betId");
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(wager, "wager");
        Objects.requireNonNull(choices, "choices");
        Objects.requireNonNull(remainder, "remainder");
        if (wager.cents() < 0 || (!forFun && (wager.cents() < 10 || wager.cents() > 1000))) {
            throw new IllegalArgumentException("Invalid wager");
        }
        Set<String> available = new HashSet<>();
        for (String choice : choices) {
            if (choice == null || choice.isBlank() || !available.add(choice)
                    || choice.strip().equalsIgnoreCase("no guess")
                    || choice.strip().equalsIgnoreCase("__no_guess__")) {
                throw new IllegalArgumentException("Invalid choices");
            }
        }
        if (available.size() < 2) {
            throw new IllegalArgumentException("At least two choices are required");
        }
        Set<UUID> seen = new HashSet<>();
        List<Participant> players = new ArrayList<>();
        for (Participant participant : participants) {
            Objects.requireNonNull(participant, "participant");
            if (!seen.add(participant.userId())) {
                throw new IllegalArgumentException("Duplicate participant");
            }
            if (participant.choice() != null) {
                if (!available.contains(participant.choice())) {
                    throw new IllegalArgumentException("Participant choice is not available");
                }
                players.add(participant);
            }
        }
        if (winningChoice == null || !available.contains(winningChoice)) {
            return new Result.Washed(WashReason.INVALID_OUTCOME);
        }
        if (players.size() < 2) {
            return new Result.Washed(WashReason.INSUFFICIENT_PLAYERS);
        }
        if (players.stream().map(Participant::choice).distinct().count() == 1) {
            return new Result.Washed(WashReason.SAME_SIDE);
        }
        List<Participant> winners = players.stream().filter(p -> p.choice().equals(winningChoice)).toList();
        if (winners.isEmpty()) {
            return new Result.Washed(WashReason.NO_WINNER);
        }
        if (forFun) {
            return new Result.Resolved(Map.of());
        }

        int loserCount = players.size() - winners.size();
        long pot = Math.multiplyExact(wager.cents(), loserCount);
        long share = pot / winners.size();
        int leftover = (int) (pot % winners.size());
        Map<UUID, Long> cents = new LinkedHashMap<>();
        for (Participant player : players) {
            cents.put(player.userId(), player.choice().equals(winningChoice) ? share : -wager.cents());
        }
        if (leftover > 0) {
            if (remainder.recipientId() != null) {
                cents.merge(remainder.recipientId(), (long) leftover, Math::addExact);
            } else {
                List<Participant> order = remainder.mode() == RemainderMode.RANDOM
                        ? shuffled(winners, betId.toString()) : winners;
                for (int i = 0; i < leftover; i++) {
                    cents.merge(order.get(i).userId(), 1L, Math::addExact);
                }
            }
        }
        Map<UUID, Money> deltas = new LinkedHashMap<>();
        cents.forEach((userId, amount) -> {
            if (amount != 0) {
                deltas.put(userId, new Money(amount));
            }
        });
        return new Result.Resolved(deltas);
    }

    private static List<Participant> shuffled(List<Participant> winners, String seed) {
        // Match the prototype's 32-bit seeded shuffle, including overflow.
        int hash = 0x811c9dc5;
        for (int i = 0; i < seed.length(); i++) {
            hash = (hash ^ seed.charAt(i)) * 0x01000193;
        }
        List<Participant> order = new ArrayList<>(winners);
        for (int i = order.size() - 1; i > 0; i--) {
            hash = (hash ^ (hash >>> 15)) * 0x85ebca6b;
            hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35;
            hash ^= hash >>> 16;
            int j = (int) (Integer.toUnsignedLong(hash) / 4294967296.0 * (i + 1));
            Participant item = order.get(i);
            order.set(i, order.get(j));
            order.set(j, item);
        }
        return order;
    }
}
