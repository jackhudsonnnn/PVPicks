package com.p2picks.app.bets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.p2picks.app.bets.PayoutCalculator.Participant;
import com.p2picks.app.bets.PayoutCalculator.Result;
import com.p2picks.app.bets.PayoutCalculator.WashReason;
import com.p2picks.shared.kernel.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutCalculatorTest {
    private static final UUID BET = id(1);
    private static final List<String> CHOICES = List.of("pass", "run", "neither");

    @Test
    void splitsPotAndExcludesNoGuessButNotSportsPass() {
        Result result = settle(List.of(p(1, "pass"), p(2, "pass"), p(3, "run"), p(4, null)),
                100, false, "pass");
        assertThat(deltas(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
                id(1), new Money(50), id(2), new Money(50), id(3), new Money(-100)));
        assertThat(deltas(settle(List.of(p(1, "pass"), p(2, "pass"), p(3, "run")),
                100, false, "run")))
                .containsEntry(id(3), new Money(200));
    }

    @Test
    void washesInvalidOutcomeInsufficientPlayersSameSideAndUnpickedWinner() {
        List<Participant> both = List.of(p(1, "pass"), p(2, "run"));
        assertThat(settle(both, 100, false, null))
                .isEqualTo(new Result.Washed(WashReason.INVALID_OUTCOME));
        assertThat(settle(both, 100, false, "outside"))
                .isEqualTo(new Result.Washed(WashReason.INVALID_OUTCOME));
        assertThat(settle(List.of(p(1, "pass"), p(2, null)), 100, false, "pass"))
                .isEqualTo(new Result.Washed(WashReason.INSUFFICIENT_PLAYERS));
        assertThat(settle(List.of(p(1, "pass"), p(2, "pass")), 100, false, "pass"))
                .isEqualTo(new Result.Washed(WashReason.SAME_SIDE));
        // The general resolution wash: a 3+ option bet whose picks left the winning option empty.
        assertThat(settle(both, 100, false, "neither"))
                .isEqualTo(new Result.Washed(WashReason.NO_WINNER));
    }

    @Test
    void leftoverCentsGoOneEachToRandomWinnersTheSameWayEveryTime() {
        List<Participant> players = new ArrayList<>();
        for (int i = 1; i <= 7; i++) players.add(p(i, "pass"));
        players.add(p(8, "run"));
        players.add(p(9, "run"));
        Result result = settle(players, 100, false, "pass");
        Map<UUID, Money> amounts = deltas(result);
        for (int i : List.of(1, 2, 3, 7)) assertThat(amounts.get(id(i))).isEqualTo(new Money(29));
        for (int i : List.of(4, 5, 6)) assertThat(amounts.get(id(i))).isEqualTo(new Money(28));
        assertThat(result).isEqualTo(settle(players, 100, false, "pass"));
    }

    @Test
    void fourFunResolvesOrWashesNormallyWithoutTrackedDeltas() {
        assertThat(deltas(settle(List.of(p(1, "pass"), p(2, "run")), 0, true, "pass")))
                .isEmpty();
        assertThat(settle(List.of(p(1, "pass"), p(2, "pass")), 0, true, "pass"))
                .isEqualTo(new Result.Washed(WashReason.SAME_SIDE));
    }

    @Test
    void omitsZeroValueWinnersFromLedgerDeltas() {
        List<Participant> players = new ArrayList<>();
        for (int i = 1; i <= 11; i++) players.add(p(i, "pass"));
        players.add(p(12, "run"));
        Map<UUID, Money> amounts = deltas(settle(players, 10, false, "pass"));
        assertThat(amounts).hasSize(11).doesNotContainKey(id(11)).containsEntry(id(12), new Money(-10));
    }

    @Test
    void rejectsInvalidInputInsteadOfTreatingItAsAValidPayout() {
        List<Participant> valid = List.of(p(1, "pass"), p(2, "run"));
        assertThatThrownBy(() -> settle(valid, 9, false, "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(valid, 1001, false, "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(List.of(p(1, "pass"), p(1, "run")), 100, false, "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settle(List.of(p(1, "unknown")), 100, false, "pass"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutCalculator.settle(BET, valid, new Money(100), false,
                List.of("pass", "No guess"), "pass")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payoutsConserveCentsAcrossManyPots() {
        Random random = new Random(921);
        for (int trial = 0; trial < 500; trial++) {
            int winners = 1 + random.nextInt(48);
            int losers = 1 + random.nextInt(50 - winners);
            long wager = 10 + random.nextInt(991);
            List<Participant> players = new ArrayList<>();
            for (int i = 0; i < winners; i++) players.add(p(i + 1, "pass"));
            for (int i = 0; i < losers; i++) players.add(p(winners + i + 1, "run"));
            Map<UUID, Money> amounts = deltas(settle(players, wager, false, "pass"));
            assertThat(amounts.values().stream().mapToLong(Money::cents).sum()).isZero();
            assertThat(amounts.values()).noneMatch(amount -> amount.cents() == 0);
            // Every winner gets the even share or one cent more.
            long share = wager * losers / winners;
            for (int i = 0; i < winners; i++) {
                long cents = amounts.containsKey(id(i + 1)) ? amounts.get(id(i + 1)).cents() : 0;
                assertThat(cents).isBetween(share, share + 1);
            }
        }
    }

    private static Result settle(
            List<Participant> players, long wager, boolean forFun, String winner) {
        return PayoutCalculator.settle(BET, players, new Money(wager), forFun, CHOICES, winner);
    }

    private static Map<UUID, Money> deltas(Result result) {
        assertThat(result).isInstanceOf(Result.Resolved.class);
        return ((Result.Resolved) result).deltas();
    }

    private static Participant p(long id, String choice) {
        return new Participant(id(id), choice);
    }

    private static UUID id(long number) {
        return new UUID(0, number);
    }
}
