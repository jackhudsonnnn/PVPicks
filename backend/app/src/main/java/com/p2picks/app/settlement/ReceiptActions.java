package com.p2picks.app.settlement;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Which receipt actions a user may take, as pure rules. The transitions enforce these, and the reads list them for
 * the viewer, so the client never re-implements who may do what. Payer actions (sending, asking to forgive) also
 * wait for an unreviewed balance-change warning; that's reported per action rather than hiding it.
 */
final class ReceiptActions {
    /** Forgiveness can't be asked again for a day after it's declined. */
    static final long DECLINE_COOLDOWN_HOURS = 24;

    /** An action the viewer may take now, unless `blockedByReview` (review the balance change first). */
    record Available(String action, boolean blockedByReview) {}

    private ReceiptActions() {}

    // ---- One receipt

    static boolean payerAction(SettlementReceiptService.Action action) {
        return action == SettlementReceiptService.Action.SENT || action == SettlementReceiptService.Action.ASK_FORGIVE;
    }

    /** Whether `actor` may take `action` on `line` at `now`: the right side of the receipt and a valid transition. */
    static boolean lineAllowed(Line line, SettlementReceiptService.Action action, UUID actor, OffsetDateTime now) {
        if (!(payerAction(action) ? line.payerId() : line.payeeId()).equals(actor)) return false;
        return switch (action) {
            case SENT, FORGIVE -> line.unpaid();
            case CONFIRM -> line.unpaid() || line.status().equals("sent");
            case DISPUTE -> line.status().equals("sent");
            case ASK_FORGIVE -> line.unpaid() && !line.hasOpenRequest() && !coolingDown(line.forgiveDeclinedAt(), now);
            case DECLINE_FORGIVE -> line.unpaid() && line.hasOpenRequest();
        };
    }

    static List<Available> forLine(Line line, UUID actor, OffsetDateTime now, boolean reviewPending) {
        List<Available> available = new ArrayList<>();
        for (SettlementReceiptService.Action action : SettlementReceiptService.Action.values()) {
            if (lineAllowed(line, action, actor, now)) {
                available.add(new Available(lineName(action), reviewPending && payerAction(action)));
            }
        }
        return List.copyOf(available);
    }

    static String lineName(SettlementReceiptService.Action action) {
        return switch (action) {
            case SENT -> "sent";
            case CONFIRM -> "confirm";
            case DISPUTE -> "dispute";
            case FORGIVE -> "forgive";
            case ASK_FORGIVE -> "ask-forgive";
            case DECLINE_FORGIVE -> "decline-forgive";
        };
    }

    // ---- Every open receipt with one person

    static boolean groupPayerAction(SettlementGroupService.Action action) {
        return action == SettlementGroupService.Action.SENT || action == SettlementGroupService.Action.ASK;
    }

    /**
     * Whether `actor` may take `action` on their open receipts with `other` (`open` only), from their side of the
     * net. A zero net can only be confirmed.
     */
    static boolean groupAllowed(List<Line> open, UUID actor, UUID other, SettlementGroupService.Action action,
            OffsetDateTime now) {
        if (open.isEmpty()) return false;
        BigInteger net = net(open, actor);
        if (net.signum() == 0) return action == SettlementGroupService.Action.PAID;
        UUID payer = net.signum() < 0 ? actor : other;
        UUID payee = payer.equals(actor) ? other : actor;
        if (!(groupPayerAction(action) ? payer : payee).equals(actor)) return false;
        List<Line> directional = open.stream().filter(line -> line.payerId().equals(payer)).toList();
        boolean sent = directional.stream().anyMatch(line -> line.status().equals("sent"));
        boolean asked = directional.stream().anyMatch(Line::hasOpenRequest);
        OffsetDateTime declined = directional.stream().map(Line::forgiveDeclinedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return switch (action) {
            case SENT, FORGIVEN -> !sent;
            case UNPAID -> sent;
            case ASK -> !sent && !asked && !coolingDown(declined, now);
            case DECLINE -> !sent && asked;
            case PAID -> true;
        };
    }

    static List<Available> forGroup(List<Line> open, UUID actor, UUID other, OffsetDateTime now,
            boolean reviewPending) {
        List<Available> available = new ArrayList<>();
        for (SettlementGroupService.Action action : SettlementGroupService.Action.values()) {
            if (groupAllowed(open, actor, other, action, now)) {
                available.add(new Available(groupName(action), reviewPending && groupPayerAction(action)));
            }
        }
        return List.copyOf(available);
    }

    static String groupName(SettlementGroupService.Action action) {
        return switch (action) {
            case SENT -> "sent";
            case PAID -> "paid";
            case UNPAID -> "unpaid";
            case FORGIVEN -> "forgiven";
            case ASK -> "ask";
            case DECLINE -> "decline";
        };
    }

    static boolean open(Line line) {
        return !line.status().equals("paid") && !line.status().equals("forgiven");
    }

    /** The open net from `actor`'s side: negative when they owe. */
    static BigInteger net(List<Line> lines, UUID actor) {
        BigInteger net = BigInteger.ZERO;
        for (Line line : lines) {
            if (!open(line)) continue;
            BigInteger amount = BigInteger.valueOf(line.amountCents());
            net = net.add(line.payerId().equals(actor) ? amount.negate() : amount);
        }
        return net;
    }

    private static boolean coolingDown(OffsetDateTime declined, OffsetDateTime now) {
        return declined != null && now.isBefore(declined.plusHours(DECLINE_COOLDOWN_HOURS));
    }
}
