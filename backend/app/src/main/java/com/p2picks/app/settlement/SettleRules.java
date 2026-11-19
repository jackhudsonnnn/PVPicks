package com.p2picks.app.settlement;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Who may settle what, as pure rules (domain rules, "Settle-ups"). A settle closes a chosen set of one pair's open
 * receipts. Seen from the person settling, the selection nets to points they're down (negative) or up (positive).
 * Points have no cash value; a settle only records that the two people consider those receipts closed.
 *
 * <ul>
 *   <li>{@code sent} ("Settle" in the app): the person down settles. Final once {@link #REJECT_WINDOW} passes,
 *       unless the other person rejects it first (which reopens the receipts).
 *   <li>{@code received} ("Mark as settled"): the person up, or either side at a zero net, closes them. Final at
 *       once: it only gives up points owed to them.
 * </ul>
 */
public final class SettleRules {
    public enum Kind {
        SENT, RECEIVED;

        String value() {
            return name().toLowerCase();
        }

        public static Kind of(String value) {
            for (Kind kind : values()) if (kind.value().equals(value)) return kind;
            return null;
        }
    }

    /** How long the other person has to reject a sent settle. */
    static final Duration REJECT_WINDOW = Duration.ofHours(48);
    /** Sent settles between two people (both directions, rejected ones included) in any rolling 24 hours. */
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
                    : "You're not down on these receipts. Mark them as settled instead.";
            case RECEIVED -> net >= 0 ? null
                    : "You're down on these receipts. Settle them instead.";
        };
    }

    /** Whether the receiver can still reject a settle at {@code now}. */
    static boolean rejectable(String kind, OffsetDateTime rejectBy, OffsetDateTime rejectedAt, OffsetDateTime now) {
        return kind.equals("sent") && rejectedAt == null && rejectBy != null && now.isBefore(rejectBy);
    }
}
