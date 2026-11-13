package com.p2picks.modules.settlement.api;

import com.p2picks.shared.kernel.Money;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TableSettlementCalculator {
    public record BetDeltas(UUID betId, Map<UUID, Money> deltas) {
        public BetDeltas {
            Objects.requireNonNull(betId, "betId");
            deltas = Map.copyOf(deltas);
        }
    }

    public record ExactBetDeltas(UUID betId, Map<UUID, BigInteger> deltas) {
        public ExactBetDeltas {
            Objects.requireNonNull(betId, "betId");
            deltas = Map.copyOf(deltas);
        }
    }

    public record Line(UUID payerId, UUID payeeId, Money amount) {}

    public record Plan(List<UUID> betIds, List<Line> lines, Money hostNet) {
        public Plan {
            betIds = List.copyOf(betIds);
            lines = List.copyOf(lines);
        }
    }

    private TableSettlementCalculator() {}

    // The caller supplies only resolved, unsettled, non-4fun bets from one table.
    public static Plan calculate(UUID hostId, List<BetDeltas> bets) {
        Objects.requireNonNull(bets, "bets");
        List<ExactBetDeltas> exact = new ArrayList<>();
        for (BetDeltas bet : bets) {
            Objects.requireNonNull(bet, "bet");
            Map<UUID, BigInteger> deltas = new HashMap<>();
            bet.deltas().forEach((userId, delta) -> deltas.put(userId, BigInteger.valueOf(delta.cents())));
            exact.add(new ExactBetDeltas(bet.betId(), deltas));
        }
        return calculateExact(hostId, exact);
    }

    // Ledger corrections can make a per-bet aggregate exceed long even when final receipts fit.
    public static Plan calculateExact(UUID hostId, List<ExactBetDeltas> bets) {
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(bets, "bets");
        if (bets.isEmpty()) {
            throw new IllegalArgumentException("At least one eligible bet is required");
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> betIds = new ArrayList<>();
        Map<UUID, BigInteger> totals = new HashMap<>();
        for (ExactBetDeltas bet : bets) {
            Objects.requireNonNull(bet, "bet");
            if (!seen.add(bet.betId())) {
                throw new IllegalArgumentException("Duplicate bet");
            }
            betIds.add(bet.betId());
            BigInteger sum = BigInteger.ZERO;
            for (Map.Entry<UUID, BigInteger> entry : bet.deltas().entrySet()) {
                BigInteger delta = entry.getValue();
                sum = sum.add(delta);
                totals.merge(entry.getKey(), delta, BigInteger::add);
            }
            if (sum.signum() != 0) {
                throw new IllegalArgumentException("Bet deltas must sum to zero");
            }
        }
        Money hostNet = new Money(totals.getOrDefault(hostId, BigInteger.ZERO).longValueExact());
        List<Map.Entry<UUID, BigInteger>> counterparties = totals.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(hostId) && entry.getValue().signum() != 0)
                .sorted(Map.Entry.<UUID, BigInteger>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<UUID, BigInteger> entry : counterparties) {
            BigInteger balance = entry.getValue();
            Money amount = new Money(balance.abs().longValueExact());
            lines.add(balance.signum() < 0
                    ? new Line(entry.getKey(), hostId, amount)
                    : new Line(hostId, entry.getKey(), amount));
        }
        return new Plan(betIds, lines, hostNet);
    }
}
