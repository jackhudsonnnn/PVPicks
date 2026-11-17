package com.p2picks.app.settlement;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Who may settle what, as pure rules (domain rules, "Settle-ups"). A settle closes a chosen set of one pair's open
 * receipts. Seen from the person settling, the selection nets to what they pay (negative) or receive (positive):
 *
 * <ul>
 *   <li>{@code sent}: the net payer says they paid. Final once {@link #REJECT_WINDOW} passes, unless the receiver
 *       rejects it first (which reopens the receipts).
 *   <li>{@code received}: the net receiver, or either side at a zero net, records it paid. Final at once: it can
 *       only give up money owed to them.
 *   <li>{@code forgiven}: the person owed gives up receipts that are all owed to them. Final at once.
 * </ul>
 */
public final class SettleRules {
    public enum Kind {
        SENT, RECEIVED, FORGIVEN;

        String value() {
            return name().toLowerCase();
        }

        public static Kind of(String value) {
            for (Kind kind : values()) if (kind.value().equals(value)) return kind;
            return null;
        }
    }

    /** How long the receiver of a sent settle has to reject it. */
    static final Duration REJECT_WINDOW = Duration.ofHours(48);
    /** Sends between two people (both directions, rejected ones included) in any rolling 24 hours. */
    static final int SENDS_PER_DAY = 3;
    /** Most receipts one settle can close. */
    static final int MAX_LINES = 500;

    private SettleRules() {}

    /** The selection's net from {@code actor}'s side: positive when the other person owes them. */
    static BigInteger net(List<Line> lines, UUID actor) {
        BigInteger net = BigInteger.ZERO;
        for (Line line : lines) {
            BigInteger amount = BigInteger.valueOf(line.amountCents());
            net = net.add(line.payeeId().equals(actor) ? amount : amount.negate());
        }
        return net;
    }

    /** Why {@code actor} can't settle {@code selection} this way, or null when they can. */
    static String problem(Kind kind, List<Line> selection, UUID actor) {
        if (selection.isEmpty()) return "Choose at least one receipt.";
        int net = net(selection, actor).signum();
        return switch (kind) {
            case SENT -> net < 0 ? null
                    : "You don't owe on these receipts. Mark them received instead.";
            case RECEIVED -> net >= 0 ? null
                    : "You owe on these receipts. Send them instead.";
            case FORGIVEN -> selection.stream().allMatch(line -> line.payeeId().equals(actor)) ? null
                    : "You can only forgive receipts owed to you.";
        };
    }

    /** Whether the receiver can still reject a settle at {@code now}. */
    static boolean rejectable(String kind, OffsetDateTime rejectBy, OffsetDateTime rejectedAt, OffsetDateTime now) {
        return kind.equals("sent") && rejectedAt == null && rejectBy != null && now.isBefore(rejectBy);
    }
}
