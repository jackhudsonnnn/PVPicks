package com.p2picks.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.p2picks.app.settlement.SettlementReceiptService.Line;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who may settle a selection, and how (domain rules, "Settle-ups"). */
class SettleRulesTest {
    private static final UUID ME = UUID.randomUUID();
    private static final UUID THEM = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-16T13:00:00Z");

    private static Line owe(long cents) {
        return new Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ME, THEM, cents, "unpaid", 0, null,
                NOW, NOW);
    }

    private static Line owed(long cents) {
        return new Line(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), THEM, ME, cents, "unpaid", 0, null,
                NOW, NOW);
    }

    @Test
    void theNetPayerSendsAndTheNetReceiverMarksReceived() {
        List<Line> iPay = List.of(owe(500), owed(300));
        assertThat(SettleRules.net(iPay, ME)).isEqualTo(java.math.BigInteger.valueOf(-200));
        assertThat(SettleRules.problem(SettleRules.Kind.SENT, iPay, ME)).isNull();
        assertThat(SettleRules.problem(SettleRules.Kind.RECEIVED, iPay, ME)).contains("Settle them");
        assertThat(SettleRules.problem(SettleRules.Kind.SENT, iPay, THEM)).contains("Mark them as settled");
        assertThat(SettleRules.problem(SettleRules.Kind.RECEIVED, iPay, THEM)).isNull();
    }

    @Test
    void anEvenSelectionIsMarkedReceivedByEitherSide() {
        List<Line> even = List.of(owe(300), owed(300));
        assertThat(SettleRules.problem(SettleRules.Kind.RECEIVED, even, ME)).isNull();
        assertThat(SettleRules.problem(SettleRules.Kind.RECEIVED, even, THEM)).isNull();
        assertThat(SettleRules.problem(SettleRules.Kind.SENT, even, ME)).isNotNull();
    }

    @Test
    void forgiveIsGoneAndAnEmptySelectionIsRefused() {
        assertThat(SettleRules.Kind.of("forgiven")).isNull();
        assertThat(SettleRules.problem(SettleRules.Kind.SENT, List.of(), ME)).isNotNull();
    }

    @Test
    void aSendCanBeRejectedOnlyWithinItsWindow() {
        OffsetDateTime by = NOW.plus(SettleRules.REJECT_WINDOW);
        assertThat(SettleRules.rejectable("sent", by, null, NOW)).isTrue();
        assertThat(SettleRules.rejectable("sent", by, null, by)).isFalse();
        assertThat(SettleRules.rejectable("sent", by, NOW, NOW)).isFalse();
        assertThat(SettleRules.rejectable("received", null, null, NOW)).isFalse();
    }
}
