package com.p2picks.app.settlement;

import com.p2picks.shared.kernel.Money;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DebtCalculator {
    public record TableBalance(UUID tableId, BigInteger unsettledResults, BigInteger pendingWagers) {
        public TableBalance {
            Objects.requireNonNull(tableId, "tableId");
            Objects.requireNonNull(unsettledResults, "unsettledResults");
            Objects.requireNonNull(pendingWagers, "pendingWagers");
            if (pendingWagers.signum() < 0) {
                throw new IllegalArgumentException("pendingWagers must be nonnegative");
            }
        }
    }

    // Positive amounts are receivable by the user; negative amounts are payable.
    public record OpenReceipt(UUID receiptId, UUID otherUserId, Money signedAmount) {
        public OpenReceipt {
            Objects.requireNonNull(receiptId, "receiptId");
            Objects.requireNonNull(otherUserId, "otherUserId");
            Objects.requireNonNull(signedAmount, "signedAmount");
            if (signedAmount.cents() == 0) {
                throw new IllegalArgumentException("Receipt amount must not be zero");
            }
        }
    }

    private DebtCalculator() {}

    public static Money calculate(UUID userId, List<TableBalance> tables, List<OpenReceipt> receipts) {
        return new Money(calculateExact(userId, tables, receipts).longValueExact());
    }

    public static BigInteger calculateExact(UUID userId, List<TableBalance> tables, List<OpenReceipt> receipts) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tables, "tables");
        Objects.requireNonNull(receipts, "receipts");
        Set<UUID> tableIds = new HashSet<>();
        BigInteger debt = BigInteger.ZERO;
        for (TableBalance table : tables) {
            Objects.requireNonNull(table, "table");
            if (!tableIds.add(table.tableId())) {
                throw new IllegalArgumentException("Duplicate table");
            }
            BigInteger trueBalance = table.unsettledResults().subtract(table.pendingWagers());
            if (trueBalance.signum() < 0) {
                debt = debt.subtract(trueBalance);
            }
        }
        Set<UUID> receiptIds = new HashSet<>();
        Map<UUID, BigInteger> personNets = new HashMap<>();
        for (OpenReceipt receipt : receipts) {
            Objects.requireNonNull(receipt, "receipt");
            if (!receiptIds.add(receipt.receiptId())) {
                throw new IllegalArgumentException("Duplicate receipt");
            }
            if (receipt.otherUserId().equals(userId)) {
                throw new IllegalArgumentException("Receipt counterparty must differ from user");
            }
            personNets.merge(receipt.otherUserId(), BigInteger.valueOf(receipt.signedAmount().cents()), BigInteger::add);
        }
        for (BigInteger net : personNets.values()) {
            if (net.signum() < 0) {
                debt = debt.subtract(net);
            }
        }
        return debt;
    }

    public static boolean canChangeCap(Money debt) {
        requireNonnegative(debt, "debt");
        return debt.cents() == 0;
    }

    public static boolean canRiskWager(Money debt, Money cap, Money wager, boolean forFun) {
        requireNonnegative(debt, "debt");
        requireNonnegative(wager, "wager");
        if (cap != null) {
            requireNonnegative(cap, "cap");
        }
        return forFun || cap == null || BigInteger.valueOf(debt.cents()).add(BigInteger.valueOf(wager.cents()))
                .compareTo(BigInteger.valueOf(cap.cents())) <= 0;
    }

    private static void requireNonnegative(Money amount, String name) {
        Objects.requireNonNull(amount, name);
        if (amount.cents() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
    }
}
