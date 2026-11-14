package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules both the receipt transitions and the per-viewer `actions` lists use. */
class ReceiptActionsTest {
    private static final UUID PAYER = UUID.randomUUID();
    private static final UUID PAYEE = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T13:00:00Z");

    private static Line line(String status, OffsetDateTime requested, OffsetDateTime declined) {
        return new Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PAYER, PAYEE, 125, status, 0,
                requested, declined, NOW, NOW);
    }

    private static List<String> names(List<ReceiptActions.Available> available) {
        return available.stream().map(ReceiptActions.Available::action).toList();
    }

    @Test
    void eachSideGetsItsOwnReceiptActions() {
        Line unpaid = line("unpaid", null, null);
        assertThat(names(ReceiptActions.forLine(unpaid, PAYER, NOW, false))).containsExactly("sent", "ask-forgive");
        assertThat(names(ReceiptActions.forLine(unpaid, PAYEE, NOW, false))).containsExactly("confirm", "forgive");
        Line sent = line("sent", null, null);
        assertThat(names(ReceiptActions.forLine(sent, PAYEE, NOW, false))).containsExactly("confirm", "dispute");
        assertThat(ReceiptActions.forLine(line("paid", null, null), PAYEE, NOW, false)).isEmpty();
        Line asked = line("forgive_asked", NOW, null);
        assertThat(names(ReceiptActions.forLine(asked, PAYEE, NOW, false)))
                .containsExactly("confirm", "forgive", "decline-forgive");
    }

    @Test
    void askingAgainWaitsADayAfterADeclineAndReviewsBlockOnlyPayerActions() {
        Line declined = line("unpaid", null, NOW.minusHours(1));
        assertThat(names(ReceiptActions.forLine(declined, PAYER, NOW, false))).containsExactly("sent");
        assertThat(names(ReceiptActions.forLine(declined, PAYER, NOW.plusHours(24), false)))
                .containsExactly("sent", "ask-forgive");
        assertThat(ReceiptActions.forLine(line("unpaid", null, null), PAYER, NOW, true))
                .allMatch(ReceiptActions.Available::blockedByReview);
        assertThat(ReceiptActions.forLine(line("unpaid", null, null), PAYEE, NOW, true))
                .noneMatch(ReceiptActions.Available::blockedByReview);
    }

    @Test
    void groupActionsFollowTheNet() {
        List<Line> owed = List.of(line("unpaid", null, null));
        assertThat(names(ReceiptActions.forGroup(owed, PAYER, PAYEE, NOW, false))).containsExactly("sent", "ask");
        assertThat(names(ReceiptActions.forGroup(owed, PAYEE, PAYER, NOW, false))).containsExactly("paid", "forgiven");
        List<Line> sent = List.of(line("sent", null, null));
        assertThat(names(ReceiptActions.forGroup(sent, PAYEE, PAYER, NOW, false))).containsExactly("paid", "unpaid");
        Line back = new Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PAYEE, PAYER, 125, "unpaid", 0,
                null, null, NOW, NOW);
        assertThat(names(ReceiptActions.forGroup(List.of(owed.getFirst(), back), PAYER, PAYEE, NOW, false)))
                .as("a zero net can only be confirmed").containsExactly("paid");
        assertThat(ReceiptActions.forGroup(List.of(), PAYER, PAYEE, NOW, false)).isEmpty();
    }
}
